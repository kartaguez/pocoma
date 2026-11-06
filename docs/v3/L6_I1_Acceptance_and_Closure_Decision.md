# Pocoma V3 — L6/I1 — Décision d'Acceptation et de Clôture Formelle de l'Incrément I1

**Date :** 6 novembre 2026  
**Dépôt :** `kartaguez/pocoma`  
**Branche :** `feat/v3-architecture`  
**SHA de référence :** `27cf9ca3d4cf86cbe2b43b67e23114d60c2394fa`  
**Autorisation humaine :** `GO_L6_I1_FINAL_RECONCILIATION_AND_CONDITIONAL_CLOSURE`  
**Statut officiel :** `L6/I1 — CLOSED — LOCAL OPERATIONAL V3 PASS WITH EXPLICIT RESERVATIONS`  

---

## 1. Décision Humaine de Clôture

En application de l'autorisation humaine `GO_L6_I1_FINAL_RECONCILIATION_AND_CONDITIONAL_CLOSURE`, après vérification contradictoire des quatre contrôles statiques résiduels, validation des corrections post-audit et constat de l'absence de toute anomalie bloquante, **l'incrément L6/I1 (« Instrumentation & Observabilité V3 ») est formellement et définitivement CLOS avec réserves explicites.**

L'objectif fonctionnel et opérationnel de L6/I1 est pleinement satisfait dans son périmètre local :
1. Une topologie Pocoma V3 réelle fonctionne localement (`runtime-monolith` avec profils `v3-read-worker,v3-http-read,v3-http-admission`, PostgreSQL 17 Alpine avec bases isolées `pocoma_write` et `pocoma_read`, Keycloak 26).
2. Un utilisateur peut s'authentifier via Keycloak en OAuth2 PKCE (S256).
3. Une opération métier V3 (`CreatePot`) est soumise et son succès terminal est constaté via des oracles stricts (`outcome == 'COMPLETED'`, `resultingPotVersion >= 1`).
4. L'état métier est restitué via la façade READ V3 (`GET /api/v3/pots/{potId}`) avec vérification d'intégrité (`id`, `label`, `potVersion`).
5. Les métriques authentiques `pocoma_v3_*` et HikariCP sont produites et collectées par Prometheus (scrape 5s).
6. Le tableau de bord Grafana provisionné (18 panneaux) restitue fidèlement ces métriques sans aucun repli trompeur vers V2, avec des unités typées et une formule mathématiquement exacte de latence moyenne HikariCP.
7. Une qualification opérationnelle ciblée (Gate B) démontre ces propriétés sans erreur.

---

## 2. Périmètre Accepté

Le périmètre formellement accepté au titre de L6/I1 comprend :
- **Instrumentation HTTP Admission V3 :** Compteurs de requêtes catégorisées, timer de latence avec histogrammes et SLO buckets, jauge de permis disponibles sur le sémaphore d'admission (`pocoma_v3_admission_*`).
- **Instrumentation HTTP READ V3 :** Mesure de latence par classification métier (`pocoma_v3_read_http_duration_seconds_*`).
- **Façade READ & Quota $Q=2$ :** Jauge de permis disponibles ($[0..2]$), compteur de rejets par saturation, timer d'exécution interne (`pocoma_v3_read_quota_*`, `pocoma_v3_read_query_*`).
- **Métrologie Workers :** Réutilisation exclusive du port `ConsumptionObservation` et exposition des cycles de polling, des temps d'acquisition de claims et des taux de matérialisation (`pocoma_v3_worker_*`, `pocoma_v3_attempt_*`).
- **Métrologie HikariCP :** Attachement de `MicrometerMetricsTrackerFactory` sur tous les pools JDBC V3 (admission, read HTTP, worker write business/control, worker read business/control).
- **Stack d'observabilité locale :** Overlay Docker Compose (`docker-compose.observability.yml`), configuration Prometheus avec scrape 5s vers le port d'administration 18081, Grafana pré-provisionné avec dashboard 18 panneaux.
- **Parcours métier et outillage :** Script d'orchestration (`run-demo-journey.py`), script d'initialisation de base de données (`setup-v3-database.py`), suite Bruno `05 Observability Demo` (8 requêtes alignées sur V3).
- **Orchestrateur de qualification :** Script maître `verify-l6-i1-observability.sh` supportant les modes `--mode=verify`, `--mode=demo` (persistant) et `--stop`.

---

## 3. Preuves Historiques Acceptées

Les preuves historiques suivantes, obtenues sur le commit `27cf9ca3d4cf86cbe2b43b67e23114d60c2394fa` (et sur le commit initial `ae6017b94191e732144fa6634fbe7ef84c180795` pour la Gate A unitaire), sont acceptées sans rejeu :

### 3.1 Gate A (`G-OBS-AUTOMATED`) — Qualification Automatisée
- **Verdict :** **PASS**
- **Durée :** 1 minute 46 secondes ($\le 240$ s)
- **Couverture :** 41/41 tests exécutés, 41 passés, 0 échec, 0 ignoré.
  - `V3AdmissionHttpTest` : 9/9 PASS
  - `V3ReadHttpContractTest` : 24/24 PASS
  - `ReadFacadesPostgresTest` : 5/5 PASS
  - `V3WorkerMetricsTest` : 3/3 PASS
- **Provenance :** `docs/testing/evidence/L6-I1/automated/TEST-*.xml`

### 3.2 Gate B Requalifiée (`G-OBS-OPERATIONAL`) — Qualification Opérationnelle
- **Verdict :** **PASS**
- **Durée :** 45 secondes ($\le 180$ s)
- **Suite Bruno CLI `05 Observability Demo` :** 8 requêtes exécutées, 8 passées, 0 erreur.
- **Progression des métriques V3 constatée dans Prometheus (`gate-b-verdict.json`) :**
  - `pocoma_v3_admission_requests_total` : $8.0 \rightarrow 11.0$ ($\Delta = +3.0$)
  - `pocoma_v3_attempt_outcomes_total` : $34.0 \rightarrow 46.0$ ($\Delta = +12.0$)
  - `pocoma_v3_read_queries_total` : $96.0 \rightarrow 109.0$ ($\Delta = +13.0$)
  - `hikaricp_connections_acquire_seconds_count` : $63495.0 \rightarrow 66005.0$ ($\Delta = +2510.0$)
- **Provenance :** `docs/testing/evidence/L6-I1/operational/` (`gate-b-verdict.json`, `journey-execution.log`, `metrics-v3-*.json`, `actuator-prometheus-*.txt`).

> [!NOTE]
> **Transparence historique :** La qualification Gate B initiale réalisée au SHA `ae6017b9`, bien qu'ayant rapporté un statut technique vert, a été déclarée **invalide pour l'objectif V3** par l'audit indépendant (anomalie AUD-L6-I1-01), car elle s'exécutait sur des endpoints historiques V2 sans activer le runtime V3 ni émettre de métriques `pocoma_v3_*`. Seule la Gate B requalifiée au SHA `27cf9ca3` constitue la preuve opérationnelle recevable.

---

## 4. Synthèse des Quatre Contrôles Statiques Résiduels

| Contrôle | Objet analysé | Résultat technique | Décision / Statut |
|---|---|---|:---:|
| **Contrôle n°1** | Réconciliation AUD-07 (allocations chemins chauds) et AUD-08 (compteur admission redondant) | Les deux anomalies sont des `IMPROVEMENT` légitimes. Les allocations (`Timer.builder`, `Tags.of`) n'ont pas d'impact sous charge locale. Les compteurs d'admission ont une cardinalité bornée sans duplication d'événements. Les sujets découpage multi-conteneurs et génération OpenAPI ont été dissociés d'AUD-07/08. | **RÉCONCILIÉ** (Reports maintenus) |
| **Contrôle n°2** | Exposition Actuator port 18081 & sécurité Spring Security | Seuls `health` et `prometheus` sont exposés (`include=health,prometheus`). Les endpoints sensibles (`env`, `beans`, `shutdown`) sont désactivés (404). Les endpoints métier `/api/v3/**` restent strictement protégés par JWT. Le port 18081 est exposé sur localhost. | **ACCEPTABLE_WITH_EXPLICIT_RESERVATION** |
| **Contrôle n°3** | Portée des preuves métriques Workers | La campagne prouve l'exécution réussie du parcours métier V3 et, indépendamment, la progression des métriques V3 sur le runtime observé. Les deltas globaux Workers ne constituent pas une attribution exhaustive des tentatives au seul parcours exécuté. | **QUALIFIÉ SINCÈREMENT** |
| **Contrôle n°4** | PostgreSQL 17 Alpine et reproductibilité du provisionnement | Le script `setup-v3-database.py` est idempotent (vérification `IF NOT EXISTS` et `to_regclass`), isole strictement les bases `pocoma_write` et `pocoma_read` sur le volume `postgres-v3-data` (préservant intact `postgres-data` V2). Droits runtime minimaux. | **REPRODUCIBLE_AND_ACCEPTABLE** |

---

## 5. Réserves Explicites

L'acceptation de L6/I1 est assortie des réserves normatives suivantes, qui devront être instruites dans les incréments ou lots ultérieurs :

1. **AUD-L6-I1-07 — Allocations sur chemins chauds (IMPROVEMENT) :** L'optimisation consistant à mettre en cache les objets `Timer` ou `Tags` dans `V3AdmissionHttpConfiguration`, `V3ReadHttpConfiguration` et `V3ReadFacadeComposition` est différée à la phase d'optimisation de performance post-L6.
2. **AUD-L6-I1-08 — Compteur d'admission potentiellement redondant (IMPROVEMENT) :** La coexistence entre `pocoma.v3.admission.requests` et `pocoma.v3.admission.http.duration_count` est maintenue à des fins de compatibilité documentaire et d'alignement avec les tableaux de bord existants.
3. **Absence de benchmark de charge :** Aucune qualification sous haute concurrence ni caractérisation des limites de saturation n'a été menée ; ces activités relèvent exclusivement de **L6/I3** (bancs k6 et qualification SPEC-09).
4. **Absence d'homologation de sécurité pour la production :** L'accès non authentifié à `/actuator/prometheus` sur le port 18081 et l'usage de mots de passe locaux en dur (`pocoma-local-only`) sont strictement limités à l'exploitation et à la démonstration locale. Une sécurisation mTLS / réseau d'administration et une gestion des secrets devront être définies pour la production (L8).
5. **Absence de corrélation individuelle des tentatives Workers :** Conformément aux règles d'anti-fuite de données sensibles et de cardinalité bornée, les métriques Prometheus ne comportent pas d'identifiants d'entités métier. La progression constatée atteste de l'activité globale du runtime et non d'un traçage unitaire par Pot.
6. **Absence de certification globale SPEC-10 :** La conformité globale du réacteur et la matrice complète de certification ne font pas partie des exigences de L6/I1.
7. **Absence de réacteur Maven complet :** Conformément à la [`Reactor_Verification_Policy.md`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/Reactor_Verification_Policy.md), aucun full reactor n'a été exécuté pour cette clôture ciblée.

---

## 6. Portée de la Clôture et Autorisation de Poursuite

### 6.1 Portée
La présente décision concerne **exclusivement l'incrément L6/I1**.  
Elle n'entraîne pas :
- La clôture du lot L6 (qui comporte les incréments I2, I3 et la gate G6).
- La clôture de la version V3.
- La levée automatique des réserves historiques des lots L1 à L5.
- Une certification d'architecture distribuée ou de passage à l'échelle.

### 6.2 Autorisation de poursuite
Le lot L6 est déclaré **en cours (OPEN)**.  
L'incrément **L6/I2 (« Exploitation & reconstruction READ »)** est formellement autorisé à entrer en phase de cadrage et d'analyse technique, conformément au plan directeur [V3_Implementation_Master_Plan.md](V3_Implementation_Master_Plan.md) et au plan consolidé [L6_Design_and_Verification_Plan.md](L6_Design_and_Verification_Plan.md). Aucun travail d'implémentation sur I2 ne pourra débuter avant ratification de son propre plan technique et obtention de son mandat `GO`.

---

## 7. Acte de Clôture Formel

```
================================================================================
POCOMA V3 — LOT L6 / INCRÉMENT I1 : ACTE OFFICIEL DE CLÔTURE FORMELLE
Statut : L6/I1 — CLOSED — LOCAL OPERATIONAL V3 PASS WITH EXPLICIT RESERVATIONS
Dépôt : kartaguez/pocoma
Branche : feat/v3-architecture
SHA immuable de clôture : 27cf9ca3d4cf86cbe2b43b67e23114d60c2394fa
Décision humaine : GO_L6_I1_FINAL_RECONCILIATION_AND_CONDITIONAL_CLOSURE

Vérifications statiques (Contrôles 1 à 4) : 4/4 SATISFAISANTS
Preuves Gate A : 41/41 PASS (1m 46s)
Preuves Gate B requalifiée : PASS (Bruno 8/8, 4 métriques V3 en progression, 45s)
Anomalies bloquantes résiduelles : 0
Réserves explicites consignées : 7 (AUD-07, AUD-08, Sécurité locale, Workers, SPEC-09, SPEC-10, Full reactor)

Lot L6 : EN COURS (OPEN)
Prochain incrément autorisé au cadrage : L6/I2 (Exploitation & reconstruction READ)
================================================================================
```
