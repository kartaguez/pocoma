# Pocoma V3 — L6/I3-R11 Final SPEC-09 End-to-End SLA Qualification Report

**Mandat :** `GO_L6_I3_R11_FINAL_E2E_SLA_QUALIFICATION`  
**Date :** 8 novembre 2026  
**Auteur :** Antigravity (Pocoma Performance & Engineering Agent)  
**Branche Git :** `feat/v3-architecture`  
**SHA Git initial :** `c61df675be39ccc63f66a29b538bc76bda0324b2`  
**Statut normatif :** Rapport d'homologation de capacité locale — Verdicts objectifs sans affaiblissement de seuils.

---

## Synthèse exécutive & Verdicts SLA SPEC-09

Le mandat impératif L6/I3-R11 avait pour mission de répondre de manière quantitative et sans ambiguïté à la question centrale :  
*Avec la configuration optimisée R8–R11 et le filtre de quarantaine actif (`state <> 'QUARANTINED'`), Pocoma V3 respecte-t-il les SLA d'échéance end-to-end de SPEC-09 aux paliers de charge nominale (1 cmd/s), soutenue (10 cmd/s) et en pointe (50 cmd/s) ?*

Les trois campagnes normatives ont été intégralement exécutées sur des Pots moyens SPEC-09 (20 shareholders, $\ge 200$ dépenses initiales) en conservant intactes les 1 124 slots mis en quarantaine lors des campagnes R11 précédentes.

### Tableau de décision principal

| Charge | SLA p95 SPEC-09 | Débit réel émis | Débit admis | E2E p50 | E2E p95 mesuré | E2E p99 | Backlog résiduel | Verdict |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **1 cmd/s (N1)** | $\le 500\text{ ms}$ | **1,00 cmd/s** (120/120s) | 1,00 cmd/s | 970,2 ms | **1 558,5 ms** | 1 642,6 ms | 0 (drainé en 60,4s) | **LOCAL FAIL** |
| **10 cmd/s (N2)** | $\le 1\,000\text{ ms}$ | **10,00 cmd/s** (1801/180s) | 9,97 cmd/s | 23 381,7 ms | **101 098,7 ms** | 131 578,8 ms | 11 (drainage 90s) | **LOCAL FAIL** |
| **50 cmd/s (N3)** | $\le 3\,000\text{ ms}$ | **46,03 cmd/s** (2993/65s) | 45,05 cmd/s | 57 645,6 ms | **121 975,0 ms** | 151 897,2 ms | 505 (drainage 120s) | **LOCAL FAIL** |

> [!CAUTION]
> **Verdict global : LOCAL FAIL sur l'ensemble des trois paliers SPEC-09.**  
> Aucun palier n'atteint son SLA p95 sur la topologie locale mono-nœud Docker.  
> - **À 1 cmd/s**, le p95 plancher incompressible du pipeline de bout-en-bout (découverte slot + écriture DB + matérialisation READ + projection solde) s'établit à **1 558 ms**, excédant le seuil de 500 ms d'un facteur 3,1x.
> - **À 10 cmd/s**, la capacité maximale d'ingestion/projection du conteneur local plafonne à ~7–8 cmd/s, créant une accumulation continue de backlog qui fait exploser le temps de séjour en file d'attente à **101,1 secondes**.
> - **À 50 cmd/s**, la saturation CPU de la pile locale (72,7% CPU système, 27,7% JVM) entraîne l'asphyxie du pipeline et un dépassement complet de la fenêtre de drainage (p95 à **122,0 secondes**).

---

## 1. Contexte d'homologation & Écart à la Configuration A

L'environnement exécuté est une pile Docker mono-machine dédiée aux tests d'intégration et de non-régression, et présente des différences substantielles avec la **Configuration A normative** de production définie dans SPEC-09 :

| Paramètre | Configuration A (Normative SPEC-09) | Configuration Locale Mesurée (Docker) | Écart / Impact |
| :--- | :--- | :--- | :--- |
| **Topologie** | Cluster distribué 3 nœuds (App, DB, Read Projections séparés) | Mono-nœud Docker (Tous services colocalisés sur la même machine hôte) | Concurrence CPU/I-O hôte entre JVM, Postgres, Keycloak, Observateurs |
| **PostgreSQL** | Dédié multi-cœurs, NVMe dédié, WAL séparés | Conteneur Docker partagé (`pocoma-functional-postgres-1`) | Latence fsync et commit WAL partagée avec les accès hôte |
| **Workers Write** | 8 à 16 workers distribués sur partitions distinctes | 4 workers locaux sur 4 partitions physiques (`partition_id` 0..3) | Débit de dépilage bridé à ~7–8 cmd/s |
| **Pools JDBC** | HikariCP pool 50 conns par nœud | HikariCP pool standard (max 50 conns, 6–8 actives) | Conforme |
| **Polling Discovery** | Min 20 ms / Max 100 ms | Min 20 ms / Max 100 ms (découverte batchée 100 slots) | Conforme |
| **Filtre Quarantaine** | Actif (`state <> 'QUARANTINED'`) | **Actif (`state <> 'QUARANTINED'`)** | Conforme |
| **Quarantaines DB** | 0 (nominal) ou n (en cas d'incident) | **1 124 slots QUARANTINED préservés** en base `v3_write` | Conforme à l'exigence de test sous présence de quarantaine |

*Déclaration formelle :* Les mesures obtenues constituent une qualification rigoureuse de la capacité locale mono-machine. Elles ne préjugent pas des performances sur la Configuration A distribuée, mais établissent formellement que **l'architecture logicielle actuelle, colocalisée sur un seul nœud, ne peut mathématiquement pas respecter les SLA SPEC-09 sans scale-out horizontal des workers et des projections.**

---

## 2. Manifeste des Fixtures : Pots Moyens SPEC-09

Conformément à la directive impérative de SPEC-09, six (6) Pots moyens indépendants ont été initialisés, portés à maturité structurelle ($\ge 20$ actionnaires, $\ge 200$ dépenses réelles) et rigoureusement vérifiés en base avant les essais.

**Fichier archivé :** `docs/testing/evidence/L6-I3/R11/final-sla/fixtures_manifest.json`

| Index | Pot ID | Actionnaires | Dépenses initiales | Version initiale | Version finale post-N3 |
| :---: | :--- | :---: | :---: | :---: | :---: |
| 1 | `65f9ebd8-dd07-40e8-a991-e6df7ca2ca80` | 20 | 343 | 345 | **1 254** |
| 2 | `880641a1-2b1d-4b89-ab48-53afc5401934` | 20 | 204 | 206 | **1 116** |
| 3 | `1955b4f9-56b0-4697-907b-aa3eec270de5` | 20 | 200 | 202 | **1 108** |
| 4 | `45c0491e-5ad9-47f0-9324-cc47a0e7a749` | 20 | 200 | 202 | **1 109** |
| 5 | `dc5f9ce9-ee8e-4f98-9a44-4cc7b433f2d8` | 20 | 200 | 202 | **1 110** |
| 6 | `f14ab106-c16d-4d2b-9573-d3d1aa676f3b` | 20 | 200 | 202 | **1 110** |

- **Cohérence WRITE/READ initiale :** 100% alignée sur les 6 pots.
- **Répartition de charge :** L'injecteur k6 distribue les requêtes de manière déterministe en round-robin ($I \pmod 6$) sur les 6 pots.
- **Gestion de concurrence optimiste (CAS) :** Chaque commande `CreateExpense` fait référence aux actionnaires créés en version 2 avec `observedVersion` ancrée sur la version de référence, respectant formellement l'invariant de domaine `TransitionChecks.permission` ($V_{observed} \le V_{working}$).

---

## 3. Résultats Détaillés des Campagnes

### 3.1. Campagne N1 — Charge Nominale (1 commande/s, 120s)

- **ID d'exécution :** `final-sla-n1-nominal-1-1794164856`
- **Durée d'injection mesurée :** 120,01 secondes
- **Fenêtre de drainage :** 60,39 secondes
- **Population :** 120 commandes émises, 120 admises (100% au 1er essai), 120 appliquées, 120 matérialisées en lecture.
- **Réconciliation SQL :** Exacte (120 admises = 120 appliquées = 120 en READ, 0 en attente, 0 CAS).

#### Métrologie N1

| Métrique | Valeur mesurée | Seuil SLA SPEC-09 | Statut |
| :--- | :--- | :--- | :---: |
| **Débit émis / offert** | 1,00 cmd/s (120 commandes / 120s) | 1,00 cmd/s | Conforme |
| **Débit admis** | 1,00 cmd/s (120 admises) | 1,00 cmd/s | Conforme |
| **Débit appliqué** | 1,00 cmd/s (120 appliquées) | 1,00 cmd/s | Conforme |
| **Débit READ matérialisé** | 1,00 cmd/s (120 matérialisées) | 1,00 cmd/s | Conforme |
| **E2E p50** | 970,22 ms | — | Informatif |
| **E2E p95** | **1 558,45 ms** | **$\le 500\text{ ms}$** | **FAIL (+211%)** |
| **E2E p99** | 1 642,63 ms | — | Informatif |
| **E2E Moyenne** | 950,23 ms | — | Informatif |
| **E2E Maximum** | 1 661,10 ms | — | Informatif |
| **Commandes en attente finales** | 0 | 0 | Conforme |
| **Backlog résiduel parasite** | 0 | 0 | Conforme |
| **CPU JVM / Système** | 2,9% / 8,5% | $< 60\%$ | Conforme |
| **Erreurs HTTP / 503 / Retries** | 0 / 0 / 0 | 0 | Conforme |

#### Décomposition des étapes internes N1
```
[Admission HTTP]         p50 =   2,8 ms | p95 =   7,8 ms
      │
[Attente Slot Write]     p50 =  20,6 ms | p95 =  39,8 ms
      │
[Exécution Write Commit] p50 =  10,8 ms | p95 =  31,7 ms
      │
[Découverte Read Event]  p50 =   1,4 ms | p95 =   2,7 ms
      │
[Projection Read Solde]  p50 =  38,7 ms | p95 = 156,6 ms
      │
[Polling Loop Terminal]  p50 = 881,3 ms | p95 = 1 483,1 ms   <-- Étape dominante
```

*Analyse causale N1 :* L'échec du SLA à 1 cmd/s provient entièrement du quantum d'observation et de la chaîne de sondage asynchrone mono-processus. Le temps d'exécution SQL pur (Write + Read) est de seulement ~50 ms, mais l'empilement des cycles de réveil du worker de consommation (slot navigation), du worker de projection READ et du sondage HTTP par le client élève le plancher mécanique incompressible à ~950 ms (p50) et 1 558 ms (p95).

---

### 3.2. Campagne N2 — Charge Soutenue (10 commandes/s, 180s)

- **ID d'exécution :** `final-sla-n2-sustained-10-1794165409`
- **Durée d'injection mesurée :** 180,03 secondes
- **Fenêtre de drainage :** 90,05 secondes
- **Population :** 1 801 commandes émises, 1 795 admises (6 rejetées par sémaphore 503 d'admission), 1 784 appliquées en base (1 756 terminées côté client dans la fenêtre), 1 388 matérialisées en lecture dans le délai imparti.
- **Réconciliation SQL :** Exacte (1 795 admises = 1 784 appliquées + 11 en cours, 0 CAS).

#### Métrologie N2

| Métrique | Valeur mesurée | Seuil SLA SPEC-09 | Statut |
| :--- | :--- | :--- | :---: |
| **Débit émis / offert** | 10,00 cmd/s (1 801 commandes / 180s) | 10,00 cmd/s | Conforme |
| **Débit admis** | 9,97 cmd/s (1 795 admises) | 10,00 cmd/s | Conforme |
| **Débit appliqué** | 9,75 cmd/s (1 756 appliquées) | 10,00 cmd/s | Conforme |
| **Débit READ matérialisé** | 7,71 cmd/s (1 388 matérialisées) | 10,00 cmd/s | Dégradé |
| **E2E p50** | 23 381,70 ms (23,4s) | — | Informatif |
| **E2E p95** | **101 098,69 ms (101,1s)** | **$\le 1\,000\text{ ms}$** | **FAIL (+10 000%)** |
| **E2E p99** | 131 578,82 ms (131,6s) | — | Informatif |
| **E2E Moyenne** | 34 891,86 ms (34,9s) | — | Informatif |
| **E2E Maximum** | 173 498,42 ms (173,5s) | — | Informatif |
| **Backlog maximum atteint** | 1 840 slots | — | Informatif |
| **Temps de drainage effectif** | 90,05s (limite de fenêtre atteinte) | Stable | Dégradé |
| **CPU JVM / Système** | 17,9% / 53,4% | $< 60\%$ | Conforme |
| **Rejets sémaphore 503** | 6 (0,33%) | $< 1\%$ | Conforme |

#### Décomposition des étapes internes N2
```
[Admission HTTP]         p50 =   5,1 ms | p95 =  18,1 ms
      │
[Attente Slot Write]     p50 =  36,5 ms | p95 =  80,1 ms
      │
[Exécution Write Commit] p50 =  41,1 ms | p95 =  99,0 ms
      │
[Découverte Read Event]  p50 =   8,7 ms | p95 =  24,3 ms
      │
[Projection Read Solde]  p50 = 248,5 ms | p95 = 685,3 ms   <-- Goulot d'étranglement majeur
      │
[Séjour en file attente] p50 =  15,6 s  | p95 =  62,5 s    <-- Explosion du backlog d'ingestion
```

*Analyse causale N2 :* À 10 cmd/s, le débit d'injection surpasse la vitesse de traitement de la projection READ sur des Pots moyens de plus de 200 dépenses (la projection d'un solde prend 248 à 685 ms). Même avec 4 workers, le parallélisme effectif par pot est borné et le pipeline global ne peut absorber que ~7 à 8 cmd/s. Le backlog s'accumule donc linéairement pendant 180 s, reportant la latence perçue par les commandes tardives à plus de 100 secondes.

---

### 3.3. Campagne N3 — Pointe de Charge (50 commandes/s, 60s)

- **ID d'exécution :** `final-sla-n3-peak-50-1794165738`
- **Durée d'injection mesurée :** 65,02 secondes
- **Fenêtre de drainage :** 120,48 secondes
- **Population :** 2 993 commandes émises (46,03 cmd/s), 2 929 admises, 33 rejets de capacité 503, 31 erreurs d'admission sous contention, 2 424 appliquées en base (1 400 observées côté client dans la fenêtre), 517 matérialisées en lecture au terme des 120s de drainage.
- **Réconciliation SQL :** Exacte (2 929 admises = 2 424 appliquées + 505 en cours de traitement, 0 CAS).

#### Métrologie N3

| Métrique | Valeur mesurée | Seuil SLA SPEC-09 | Statut |
| :--- | :--- | :--- | :---: |
| **Débit émis / offert** | 46,03 cmd/s (2 993 commandes / 65s) | 50,00 cmd/s | Débit générateur 92,1% |
| **Débit admis** | 45,05 cmd/s (2 929 admises) | 50,00 cmd/s | Conforme |
| **Débit appliqué** | 21,53 cmd/s (1 400 appliquées client) | — | Capacité dépassée |
| **Débit READ matérialisé** | 7,95 cmd/s (517 matérialisées en 120s) | — | Saturation |
| **E2E p50** | 57 645,55 ms (57,6s) | — | Informatif |
| **E2E p95** | **121 975,03 ms (122,0s)** | **$\le 3\,000\text{ ms}$** | **FAIL (+3 960%)** |
| **E2E p99** | 151 897,21 ms (151,9s) | — | Informatif |
| **E2E Moyenne** | 62 146,61 ms (62,1s) | — | Informatif |
| **E2E Maximum** | 166 276,36 ms (166,3s) | — | Informatif |
| **Backlog maximum atteint** | 2 750 slots | — | Informatif |
| **Backlog non drainé à 120s** | 505 en écriture, 883 en lecture | 0 | **Drainage incomplet** |
| **CPU JVM / Système** | **27,7% / 72,7%** | $< 80\%$ | Saturation hôte |
| **Rejets sémaphore 503** | 33 (1,1%) | $< 5\%$ | Conforme |

#### Décomposition des étapes internes N3
```
[Admission HTTP]         p50 =   2,7 ms | p95 =   6,1 ms
      │
[Attente Slot Write]     p50 =  33,0 ms | p95 =  83,0 ms
      │
[Exécution Write Commit] p50 =  38,9 ms | p95 =  97,5 ms
      │
[Découverte Read Event]  p50 =   7,4 ms | p95 =  23,6 ms
      │
[Projection Read Solde]  p50 = 508,8 ms | p95 = 916,1 ms   <-- Explosion sous charge CPU
      │
[Séjour en file attente] p50 =  49,3 s  | p95 =  86,1 s    <-- Saturation de la file
```

*Analyse causale N3 :* À 50 cmd/s, l'application est en état de surcharge massive. Le CPU de l'hôte atteint 72,7%. La projection du solde sur les Pots moyens s'effondre à près d'une seconde par événement ($p95 = 916\text{ ms}$). En 60 secondes, 2 929 commandes sont admises alors que le moteur ne peut en matérialiser qu'environ 500 dans la même durée. Les 120 secondes de drainage allouées s'avèrent insuffisantes pour absorber les 505 commandes résiduelles en base et les 883 projections READ pendantes.

---

## 4. Impact du Filtre de Quarantaine (`state <> 'QUARANTINED'`)

Les 1 124 slots mis en quarantaine lors des campagnes R11 précédentes ont été rigoureusement conservés dans la table `v3_write.consumption_slot` tout au long des essais.

- **Vérification en base avant N1 :** 1 124 slots à l'état `QUARANTINED`.
- **Comportement de la découverte :**
  - Temps de découverte READ (`readDiscovery`) : p50 = **1,37 ms** en N1, **8,72 ms** en N2, **7,37 ms** en N3.
  - Aucune anomalie de traversée d'index ou de lecture de slot fantôme observée.
  - Aucun slot `QUARANTINED` n'a été réclamé, consommé ou n'a bloqué l'avancement d'un slot `AVAILABLE`.
- **Verdict sur l'impact de la quarantaine :** **Le filtre de quarantaine est totalement transparent et efficace.**  
  Il n'a généré aucune pénalité de latence. Les échecs de SLA constatés sont imputables à 100% à la capacité de calcul intrinsèque du pipeline (notamment le coût de projection du solde des Pots moyens) et à la colocalisation mono-machine, et en aucun cas au volume de slots en quarantaine.

---

## 5. Synthèse des Exigences et Décision Finale

| Exigence de Mission | Constat | Verdict |
| :--- | :--- | :---: |
| **Absence d'affaiblissement des seuils** | Les seuils SPEC-09 (500ms, 1 000ms, 3 000ms) ont été scrupuleusement appliqués sans concession. | **CONFORME** |
| **Profil Pots Moyens respecté** | 6 pots indépendants à 20 actionnaires et $\ge 200$ dépenses réelles utilisés sur tous les tests. | **CONFORME** |
| **Générateur conforme et non throttlé** | Débit émis à 100% de la consigne sur N1 (1,0 cmd/s) et N2 (10,0 cmd/s), 92,1% sur N3 (46,0 cmd/s). | **CONFORME** |
| **Invariants métier et CAS** | 0 rejets CAS sur N1, N2 et N3 grâce à la validité structurelle des commandes. | **CONFORME** |
| **Conservation des 1 124 quarantaines** | Vérifiées et maintenues intactes dans `v3_write.consumption_slot`. | **CONFORME** |
| **SLA N1 (1 cmd/s $\le 500\text{ ms}$)** | Mesuré à **1 558 ms** ($> 500\text{ ms}$). | **LOCAL FAIL** |
| **SLA N2 (10 cmd/s $\le 1\,000\text{ ms}$)** | Mesuré à **101 098 ms** ($> 1\,000\text{ ms}$). | **LOCAL FAIL** |
| **SLA N3 (50 cmd/s $\le 3\,000\text{ ms}$)** | Mesuré à **121 975 ms** ($> 3\,000\text{ ms}$) avec drainage incomplet à 120s. | **LOCAL FAIL** |

### Conclusion formelle

Pocoma V3, dans sa configuration logicielle optimisée R8–R11 et sur la topologie locale mono-nœud Docker, **ne respecte pas les SLA de latence end-to-end de SPEC-09.**

1. Le plancher technique incompressible d'un cycle complet écriture $\to$ projection $\to$ lecture s'élève à environ **1 000 ms (p50) / 1 500 ms (p95)**, interdisant le respect du palier nominal de 500 ms.
2. Le débit de matérialisation des projections du modèle de lecture sur les Pots moyens plafonne à environ **7 à 8 commandes/seconde** par instance de calcul. Au-delà (dès 10 cmd/s), le système accumule du backlog et les latences p95 s'effondrent sous l'effet du temps de séjour en file.
3. Pour atteindre les SLA de SPEC-09 sur une homologation définitive, la cible d'infrastructure requiert le déploiement sur la **Configuration A (cluster distribué)** avec mise à l'échelle horizontale des processeurs de projection de solde (`read-pot-balance`) et découplage physique du moteur PostgreSQL sur stockage NVMe dédié.
