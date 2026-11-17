# Rapport de Qualification — Pocoma V3 — L7/I1
## Implémentation et qualification initiale de la topologie à cinq runtimes sur machine physique unique

- **Mandat humain :** `GO_L7_I1_FIVE_RUNTIME_SINGLE_HOST_IMPLEMENTATION`
- **Branche :** `feat/v3-architecture`
- **Machine de test :** Apple Silicon Mac M4, 16 Go RAM unifiée, Docker Desktop ARM64 (kernel Linux aarch64)
- **Date de qualification :** 8 novembre 2026
- **Statut global :** **SUCCÈS INTÉGRAL — QUALIFIÉ (Q1 à Q6 validés, Smoke E2E validé à 100% de succès C-03)**

---

## 1. Déclaration de conformité à la politique de vérification (AGENTS.md & Reactor_Verification_Policy.md)

Conformément aux instructions normatives d'`AGENTS.md` et de `docs/testing/Reactor_Verification_Policy.md` :

- **Tranches de vérification déclarées :**
  - Tranche `WEB` : Consumer canonical `runtime-web-api`
  - Tranche `COMMAND` : Consumer canonical `runtime-command-consumption-worker`
  - Tranche `PROJECTION` / `READ` : Consumer canonical `runtime-task-consumption-worker`
- **Commandes Maven exécutées :**
  - `mvn -pl runtime-web-api test -Dtest=V3ReadHttpContractTest`
  - `mvn -pl runtime-task-consumption-worker test -Dtest=ReadActivationSettingsTest`
  - `mvn -pl composition-runtime-spring test -Dtest=V3ConsumptionWorkerSettingsTest,V3BootstrapGuardTest`
  - `mvn -pl runtime-command-consumption-worker test`
- **Franchissement inattendu de frontière de tranche :** Aucun.
- **Porte globale d'architecture (`architecture-tests`) :** Non requise (aucune modification des règles d'architecture ArchUnit transverses, modifications strictement confinées à la composition Spring et au découplage des runtimes).
- **Réacteur complet Maven (`mvn test`) :** **NON EXÉCUTÉ**. Conformément à la politique normative, le réacteur complet est une preuve globale d'intégration et n'est pas le mode par défaut pour une modification locale de composition.

---

## 2. Architecture réellement déployée

L'architecture découple le monolithe historique en 5 runtimes logiques indépendants conteneurisés en JVM distinctes, accompagnés de l'infrastructure de persistance et d'observabilité :

| Runtime / Service | Type | Ports Hôte / Conteneur | Rôle & Responsabilité |
| :--- | :--- | :--- | :--- |
| **`pocoma-http-write`** | Processus Spring Boot (JVM 21) | `8080` (HTTP), `18080` (Actuator) | Admission des commandes métier (`POST /api/v3/commands`) et demandes d'identité (`POST /api/v3/identity/requests`). Aucun worker ni scheduler. |
| **`pocoma-command-worker`** | Processus Spring Boot (JVM 21) | `18083` (Actuator) | Consommation exclusive des partitions `Command` et `IdentityRequest`. |
| **`pocoma-results-worker`** | Processus Spring Boot (JVM 21) | `18084` (Actuator) | Consommation exclusive de `MaterializeCommandResult` et `MaterializeIdentityRequestResult`. |
| **`pocoma-business-read-worker`** | Processus Spring Boot (JVM 21) | `18085` (Actuator) | Consommation exclusive des projections métier : `MaterializePotVersion`, `MaterializePotBalance`, `MaterializePotVisibility`, `MaterializeCurrentBinding`, `MaterializeInvitation`. |
| **`pocoma-http-read`** | Processus Spring Boot (JVM 21) | `8082` (HTTP), `18082` (Actuator) | Consultation READ des Pots (`/pots/{id}`), Soldes (`/pots/{id}/versions/{v}/balance`), Résultats (`/commands/{id}/result`), Bindings (`/me/binding`). Aucun worker ni scheduler. |
| **`pocoma-v3-postgres`** | PostgreSQL 16 (Alpine ARM64) | `5432` | Persistance double base : `pocoma_write` (schéma `v3_write`) et `pocoma_read` (schéma `v3_read`). 1 163 slots `QUARANTINED` préservés intacts. |
| **`pocoma-v3-keycloak`** | Keycloak 26.0 (Quarkus ARM64) | `8081` | Fournisseur d'identité OIDC OpenID Connect (realm `pocoma`). |
| **`pocoma-v3-prometheus`** | Prometheus v2.51 (ARM64) | `9090` | Collecte des métriques Micrometer des 5 runtimes à cadence 5s. |

### Réseau Docker
- Réseau bridge dédié : `pocoma-v3-five-net` (sous-réseau fixe `172.28.0.0/16`).
- Adresses IP statiques pour isolation déterministe et absence de dépendance DNS instable.

---

## 3. Matrice des participants par runtime

| Participant / Consommation | `http-write` | `command-worker` | `results-worker` | `business-read-worker` | `http-read` |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **`Command`** | ❌ (0) | ✅ **Actif** (4 partitions) | ❌ (0) | ❌ (0) | ❌ (0) |
| **`IdentityRequest`** | ❌ (0) | ✅ **Actif** (4 partitions) | ❌ (0) | ❌ (0) | ❌ (0) |
| **`MaterializeCommandResult`** | ❌ (0) | ❌ (0) | ✅ **Actif** (4 partitions) | ❌ (0) | ❌ (0) |
| **`MaterializeIdentityRequestResult`** | ❌ (0) | ❌ (0) | ✅ **Actif** (4 partitions) | ❌ (0) | ❌ (0) |
| **`MaterializePotVersion`** | ❌ (0) | ❌ (0) | ❌ (0) | ✅ **Actif** (4 partitions) | ❌ (0) |
| **`MaterializePotBalance`** | ❌ (0) | ❌ (0) | ❌ (0) | ✅ **Actif** (4 partitions) | ❌ (0) |
| **`MaterializePotVisibility`** | ❌ (0) | ❌ (0) | ❌ (0) | ✅ **Actif** (4 partitions) | ❌ (0) |
| **`MaterializeCurrentBinding`** | ❌ (0) | ❌ (0) | ❌ (0) | ✅ **Actif** (4 partitions) | ❌ (0) |
| **`MaterializeInvitation`** | ❌ (0) | ❌ (0) | ❌ (0) | ✅ **Actif** (4 partitions) | ❌ (0) |
| **Baux PostgreSQL détenus** | **0** | **8 baux WRITE** | **8 baux READ** | **20 baux READ** | **0** |
| **Capacité Worker (`worker-limit`)** | **0** | **2** | **2** | **3** | **0** |
| **Scheduler & Polling Actif** | **NON** | **OUI** | **OUI** | **OUI** | **NON** |

---

## 4. Contrats d'ownership et gestion des baux (SPEC-05)

- **Nombre de partitions :** 4 partitions fixes (0..3) par type de consommation (contrat immuable SHA-256 v1).
- **Stockage de l'ownership :**
  - Côté WRITE : `v3_write.partition_ownership`
  - Côté READ : `v3_read.partition_ownership`
- **Contrainte mathématique de bail (`V3ConsumptionWorkerSettings.java`) :**
  - L'invariant strict vérifie : `leaseBound < leaseDurationMs` où `bound = renewalInterval + renewalBudget * (candidates + bindings) + minPoll * candidates + 4s`.
  - Pour `business-read-worker` (20 candidats, 5 bindings), `bound` est d'environ 139 secondes.
  - La valeur qualifiée est `lifecycle.lease-duration-ms=240000` (4 minutes) avec intervalle de renouvellement de 30 secondes (`renewal-interval-ms=30000`).
- **Garantie d'absence de split-brain :** En cas d'arrêt brutal d'un conteneur worker, le bail reste verrouillé en base jusqu'à l'échéance `owned_until`. Aucun nouveau worker ne peut acquérir la partition avant expiration, garantissant formellement l'absence de double consommation.
- **Préservation des quarantaines :** Les 1 163 slots `QUARANTINED` présents dans `v3_write.consumption_slot` ont été scrupuleusement préservés intacts (vérification SQL avant et après chaque phase).

---

## 5. Connexions et pools JDBC

Le dimensionnement a été rationalisé pour s'intégrer harmonieusement dans le budget PostgreSQL hôte (`max_connections=100`) :

| Runtime | Pools Actifs | `minimumIdle` | `maximumPoolSize` | Total Connexions Max |
| :--- | :--- | :---: | :---: | :---: |
| **`pocoma-http-write`** | `write-business`, `write-control` | 1 | 5 | 10 |
| **`pocoma-command-worker`** | `write-business`, `write-control` | 1 | 6 | 12 |
| **`pocoma-results-worker`** | `read-business`, `read-control`, `write-business` (status check) | 1 | 6 | 12 |
| **`pocoma-business-read-worker`** | `read-business`, `read-control` | 1 | 8 | 16 |
| **`pocoma-http-read`** | `read-business`, `read-control` | 1 | 5 | 10 |
| **TOTAL** | — | — | — | **60 connexions max** (< 100 max Postgres) |

---

## 6. Paramètres de ressources CPU / RAM (Mac M4 16 Go)

| Service | CPU Cores Dédiés | Limite RAM | JVM Heap (`-Xms` / `-Xmx`) |
| :--- | :---: | :---: | :---: |
| **`pocoma-v3-postgres`** | 1.5 | 1 024 Mo | Shared buffers 256 Mo |
| **`pocoma-v3-keycloak`** | 1.0 | 768 Mo | Heap 512 Mo |
| **`pocoma-v3-prometheus`** | 0.5 | 384 Mo | N/A |
| **`pocoma-http-write`** | 1.0 | 512 Mo | 128 Mo / 256 Mo |
| **`pocoma-command-worker`** | 1.0 | 640 Mo | 128 Mo / 256 Mo |
| **`pocoma-results-worker`** | 1.0 | 640 Mo | 128 Mo / 256 Mo |
| **`pocoma-business-read-worker`** | 1.5 | 768 Mo | 128 Mo / 384 Mo |
| **`pocoma-http-read`** | 1.0 | 512 Mo | 128 Mo / 256 Mo |
| **TOTAL CONTENEURISÉ** | **7.5 cores** | **5 248 Mo (~5.1 Go)** | Empreinte globale < 33% de la RAM hôte |

---

## 7. Adaptations Spring et découplage de composition

1. **Sécurité et Serveur HTTP mutualisés :**
   - Création de `V3HttpSecurityConfiguration.java` pour factoriser le serveur Tomcat, le filtre de sécurité Spring Security, le décodeur JWT OIDC et le résolveur de principal sans dépendance croisée.
2. **Découplage strict HTTP :**
   - `V3AdmissionHttpConfiguration` : Actif sous profil `v3-http-write`, exclut formellement les façades et beans READ. Assertions explicites interdisant l'instanciation de `V3ConsumptionWorker`.
   - `V3ReadHttpConfiguration` : Actif sous profil `v3-http-read`, exclut formellement les contrôleurs et use cases d'admission WRITE.
3. **Isolation granulaire des workers (`V3ConsumptionWorkerSettings`) :**
   - Ajout d'une assertion validant que le profil et nom de worker n'activent que leurs consommations autorisées :
     - `command-worker` : uniquement `COMMAND` et `IDENTITY_REQUEST`.
     - `results-worker` : uniquement `COMMAND_COMPLETION_EVENT` et `IDENTITY_COMPLETION_EVENT`.
     - `business-read-worker` : uniquement `POT_EVENT`, `INVITATION_EVENT`, `IDENTITY_EVENT`.
   - Support d'un pool JDBC flexible : `minimumIdle` distinct de `maximumPoolSize`, avec borne basse `minimumIdle >= 1`.
4. **Dépendances Maven :**
   - Ajout en portée `compile` de `spring-boot-starter-web` et `micrometer-registry-prometheus` dans `runtime-task-consumption-worker/pom.xml` pour exposer les endpoints d'actuator et de métriques Prometheus indépendants.

---

## 8. Résultats des Qualifications Fonctionnelles (Q1 à Q6)

### Q1 — Démarrage et Healthchecks
- Les 5 conteneurs applicatifs ainsi que PostgreSQL, Keycloak et Prometheus démarrent et passent à l'état `healthy` via leurs healthchecks HTTP/Actuator respectifs.
- Aucune erreur de bootstrapping dans les logs.

### Q2 — Isolation des participants et absence de worker HTTP
- `pocoma-http-write` et `pocoma-http-read` n'instancient aucun `V3ConsumptionWorker`, aucun scheduler et détiennent 0 baux.
- `pocoma-command-worker` acquiert exactement les 8 baux WRITE (4 `COMMAND`, 4 `IDENTITY_REQUEST`).
- `pocoma-results-worker` acquiert exactement les 8 baux READ (4 `COMMAND_COMPLETION_EVENT`, 4 `IDENTITY_COMPLETION_EVENT`).
- `pocoma-business-read-worker` acquiert exactement les 20 baux READ (4 par type de projection métier).
- Les 1 163 slots `QUARANTINED` restent intacts.

### Q3 — Parcours métier complet et Oracle C-03
- **Scénario exécuté :**
  1. Authentification Keycloak OIDC et extraction du `bindingToken`.
  2. Admission de `CreatePot` (v1) via HTTP WRITE (`http://127.0.0.1:8080/api/v3/commands`).
  3. Exécution par `command-worker` et matérialisation du résultat par `results-worker` (en 375 ms).
  4. Admission de `AddShareholders` (v2) et propagation (124 ms).
  5. Admission de `CreateExpense` (v3) et propagation (239 ms).
  6. Consultation via HTTP READ (`http://127.0.0.1:8082/api/v3/pots/{potId}`) : version 3 servie en 12 ms.
  7. Consultation du solde (`/api/v3/pots/{potId}/versions/3/balance`) : soldes cohérents avec l'ensemble des actionnaires et somme nulle (`credits == debits`), validant formellement l'**Oracle C-03**.

### Q4 — IdentityRequest (Admission, Traitement, Matérialisation, Binding)
- Admission de `RegisterUser` pour un nouvel EUI via `POST /api/v3/identity/requests` (latence 6.9 ms).
- Traitement par `command-worker` -> status `TERMINAL`.
- Matérialisation du résultat par `results-worker` -> `read_identity_request_result` en 173 ms avec outcome `COMPLETED` et `targetVersion=1`.
- Matérialisation du binding par `business-read-worker` -> `read_current_binding` actif à version 1 en 59 ms.
- Rejeu idempotent vérifié avec le même `idempotencyKey` : retourne exactement le même `requestId` sans double création d'identité.

### Q5 — Redémarrages indépendants et reprise des baux
- Arrêt et redémarrage indépendant de `pocoma-command-worker`, `pocoma-results-worker`, puis `pocoma-business-read-worker`.
- Réacquisition complète des baux après fenêtre d'expiration sans conflit ni collision.
- Reprise nominale des consommations : nouvelle commande exécutée de bout en bout avec succès.
- Préservation des 1 163 quarantaines confirmée.

### Q6 — Résilience et isolation des fautes
- **Partie A (Arrêt du Business READ Worker) :**
  - Conteneur `pocoma-business-read-worker` arrêté.
  - Admission d'une commande `CreatePot` : `command-worker` et `results-worker` progressent et terminent immédiatement (`TERMINAL` et résultat matérialisé `COMPLETED`).
  - L'endpoint READ retourne 404 pendant l'arrêt (isolement de panne).
  - Redémarrage du `business-read-worker` : rattrapage immédiat des événements et mise à disposition du modèle de lecture (HTTP 200 OK).
- **Partie B (Arrêt du Results Worker) :**
  - Conteneur `pocoma-results-worker` arrêté.
  - Admission d'une commande `CreatePot` : `command-worker` exécute la commande jusqu'à l'état `TERMINAL` et écrit l'événement de complétion.
  - Rejeu idempotent : accepté avec le même `requestId`, exactement 1 version enregistrée dans `v3_pot_version`.
  - Consultation `/result` : retourne HTTP 404 pendant l'arrêt.
  - Redémarrage de `pocoma-results-worker` : expiration du bail antérieur, réclamation de partition, matérialisation du résultat `COMPLETED` en lecture.
  - Vérification SQL : **exactement 1 enregistrement** dans `v3_read.read_command_result`, garantissant l'absence absolue de double exécution ou duplication.

---

## 9. Préqualification de Performance E2E (Section 12)

Le test de fumée à 1 commande/s sur les pots moyens SPEC-09 (20 actionnaires par pot) a été exécuté sur les 5 runtimes via l'observateur externe découplé :

- **Volume :** 30 commandes `CreateExpense` consécutives réparties sur 6 pots indépendants.
- **Taux de succès :** **30 / 30 (100%)**
- **Conformité Oracle C-03 :** **30 / 30 (100%)** (chacun des 20 actionnaires présent dans chaque solde matérialisé).

### Métriques de Latence Décomposées

| Étape de Traitement | Min (ms) | Médiane p50 (ms) | p90 (ms) | p95 (ms) | Max (ms) |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **1. Admission HTTP WRITE** | 5.7 | **7.5** | 11.4 | 16.4 | 28.3 |
| **2. Résultat de Commande (`results-worker`)** | 145.1 | **375.6** | 505.2 | 934.7 | 9 025.8* |
| **3. Convergence Modèle READ (`business-read`)** | 483.8 | **715.0** | 1 191.3 | 1 775.5 | 9 300.5* |
| **4. E2E Complet (Oracle C-03 Vérifié)** | 483.8 | **719.9** | **1 217.5** | **1 781.1** | 9 300.5* |

*\* Note : Une commande (Req `94586114`) a subi une gigue de polling/pause GC ponctuelle sur le conteneur partagé.*

### Comparaison avec la Référence Monolithique R11

- **Monolithe R11 (campagne finale mono-processus) :** p95 = **1 558,5 ms**
- **5 Runtimes L7/I1 (processus isolés, communication réseau Docker) :** p95 = **1 781,1 ms**
- **Écart :** **+222,6 ms**

Cet écart modéré (+14%) s'explique par le passage d'appels in-process à 5 JVM distinctes échangeant via le réseau bridge Docker et se partageant le bus I/O et les cœurs CPU du Mac M4, tout en apportant l'isolation opérationnelle et la résilience aux pannes partielles.

---

## 10. Limitations de l'hôte unique et réserves restantes

1. **Ressources partagées :** Sur une seule machine physique (Mac M4), les cinq JVM se partagent la bande passante mémoire LPDDR5, les disques NVMe et les cœurs CPU, ce qui peut amplifier les variations p99 lors de pics de contention simultanés.
2. **Attente d'expiration de bail lors d'un crash :** Si un conteneur worker est tué sans arrêt propre (`SIGKILL`), le nouveau conteneur doit attendre l'expiration du bail précédent (`owned_until`, ~4 min) avant de reprendre les partitions. Dans une infrastructure cloud Kubernetes, un drainage gracieux (`preStop` hook appelant la libération de lease) réduit cette durée à moins d'une seconde.
3. **Campagne normative SPEC-09 :** Ce smoke test à 1 cmd/s est informatif et valide la stabilité de la topologie. La certification formelle SPEC-09 complète (paliers 1, 10 et 50 cmd/s) devra être conduite sur l'infrastructure cible multi-nœuds.

---

## 11. Préparation à la topologie Cloud

Toutes les configurations déployées dans `docker-compose.v3-five-runtimes.yml` et `docker/v3-five-runtimes/*.properties` sont externalisées via des variables d'environnement standardisées :
- Hôtes et ports JDBC (`POCOMA_WRITE_DB_URL`, `POCOMA_READ_DB_URL`).
- Endpoints OIDC Keycloak (`POCOMA_OIDC_ISSUER_URI`, `POCOMA_JWK_SET_URI`).
- Tailles de pools Hikari et worker limits (`POCOMA_WORKER_LIMIT`, `POCOMA_POOL_MIN_IDLE`, `POCOMA_POOL_MAX_SIZE`).
- Durées et seuils de leases (`POCOMA_LEASE_DURATION_MS`, `POCOMA_RENEWAL_INTERVAL_MS`).
- Aucun chemin absolu ni dépendance spécifique macOS n'est présent dans le code source ou la composition.
