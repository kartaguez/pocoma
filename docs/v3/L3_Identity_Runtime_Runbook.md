# Pocoma V3 — L3 — Runbook d'Exploitation et de Reproduction Runtime Identity

**Date :** 1er novembre 2026  
**Branche :** `feat/v3-architecture`  
**Statut :** DOCUMENT D'EXPLOITATION AUTONOME (DEV / TEST RUNTIME)  
**Profils actifs :** `v3-consumption-worker-dev` (Worker) / `v3-monolith-dev` (Monolithe)  

---

## 1. Principes Directeurs et Périmètre

Ce runbook décrit la mise en œuvre autonome, la configuration, l'amorçage administratif, l'injection d'intentions contrôlées et le diagnostic opérationnel du composant **Identity WRITE V3**.

### Règles fondamentales de sécurité et d'architecture :
1. **Environnement DEV dédié :** Ces outils et procédures sont strictement réservés à des bases de test PostgreSQL jetables en environnement DEV. Aucun reset implicite ou migration destructive n'est autorisé en production.
2. **Autorité réelle Identity :** La configuration active utilise `pocoma.v3.worker.identity-authority=real` adossée au schéma PostgreSQL V6 (`v3_eui_authority`, `v3_eui_version`, `v3_pocoma_user`).
3. **Double racine supportée :** L'exécution est supportée de manière identique dans le Worker asynchrone (`pocoma-runtime-command-consumption-worker`) et dans le Monolithe (`pocoma-runtime-monolith`).
4. **Propriétaire transactionnel unique :** Toutes les écritures partagent le `JpaTransactionManager` (`SpringAttemptTransactions`), garantissant un poisoning réciproque JDBC/JPA sans fuite de verrou.
5. **Confidentialité des tokens :** Les jetons `bindingToken` ne sont jamais exposés dans les représentations `toString()`, les logs d'erreurs, les rejets de requêtes, ou les événements de complétion.

---

## 2. Prérequis d'Infrastructure

### 2.1 Moteur de base de données PostgreSQL
- Version : PostgreSQL 17 (image certifiée `postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73`).
- Deux bases distinctes sur la même instance (ou deux instances) :
  - Base WRITE : base principale de travail (ex. `pocoma`).
  - Base READ technique : base dédiée aux projections techniques (ex. `pocoma_read`).
- Extension requise : `pgcrypto` activée dans le schéma public des deux bases (`create extension pgcrypto with schema public`).

### 2.2 Variables d'environnement administratives

Avant tout amorçage, définir les variables suivantes dans une session shell sécurisée :

```sh
export POCOMA_I5_ADMIN_URL="jdbc:postgresql://localhost:5432/pocoma"
export POCOMA_I5_ADMIN_USER="postgres"
export POCOMA_I5_ADMIN_PASSWORD="votre_mot_de_passe_admin"
export POCOMA_I5_REACTIVATION_ROLE="pocoma_react"
export POCOMA_I5_REACTIVATION_PASSWORD="mot_de_passe_react"
export POCOMA_I5_INJECTOR_ROLE="pocoma_injector"
export POCOMA_I5_INJECTOR_PASSWORD="mot_de_passe_injector"

# Optionnel : si psql s'exécute dans un conteneur Docker jetable :
# export POCOMA_I5_PSQL_CONTAINER="mon-conteneur-postgres"
```

---

## 3. Fichier de Configuration Runtime (`runtime.properties`)

Créer un fichier de propriétés avec des permissions POSIX strictes (`chmod 0600 runtime.properties`) :

```properties
# --- Environnement et Profil ---
pocoma.v3.worker.enabled=true
pocoma.v3.worker.environment=DEV
pocoma.v3.worker.worker-name=l3-identity-worker

# --- Configuration de l'Autorité Identity (R-ID) ---
pocoma.v3.worker.identity-authority=real
pocoma.v3.worker.identity-request=true
pocoma.v3.worker.fixture-identity=false

# --- Activation Command (Optionnel pour I4 R-ID-POT, désactivé pour Identity seule) ---
pocoma.v3.worker.command-enabled=false
pocoma.v3.worker.technical-probes=false

# --- Limites de Concurrence et Partitions ---
pocoma.v3.worker.worker-limit=4
pocoma.v3.worker.consumption-limits=IdentityRequest=4
pocoma.v3.worker.partitions=IdentityRequest=0|1|2|3

# --- Sources de Données WRITE (Business & Control) ---
pocoma.v3.worker.write-business.url=jdbc:postgresql://localhost:5432/pocoma?currentSchema=pocoma_dev
pocoma.v3.worker.write-business.username=pocoma_dev_w
pocoma.v3.worker.write-business.password=secret_w
pocoma.v3.worker.write-business.maximum-pool-size=4

pocoma.v3.worker.write-control.url=jdbc:postgresql://localhost:5432/pocoma?currentSchema=pocoma_dev
pocoma.v3.worker.write-control.username=pocoma_dev_w
pocoma.v3.worker.write-control.password=secret_w
pocoma.v3.worker.write-control.maximum-pool-size=4

# --- Sources de Données READ Technique (Business & Control) ---
pocoma.v3.worker.read-business.url=jdbc:postgresql://localhost:5432/pocoma_read?currentSchema=pocoma_dev
pocoma.v3.worker.read-business.username=pocoma_dev_r
pocoma.v3.worker.read-business.password=secret_r
pocoma.v3.worker.read-business.maximum-pool-size=4

pocoma.v3.worker.read-control.url=jdbc:postgresql://localhost:5432/pocoma_read?currentSchema=pocoma_dev
pocoma.v3.worker.read-control.username=pocoma_dev_r
pocoma.v3.worker.read-control.password=secret_r
pocoma.v3.worker.read-control.maximum-pool-size=4

# --- Pilotage Administratif ---
pocoma.v3.pilot.write.database=pocoma
pocoma.v3.pilot.write.schema=pocoma_dev
pocoma.v3.pilot.write.username=pocoma_dev_w

pocoma.v3.pilot.read.database=pocoma_read
pocoma.v3.pilot.read.schema=pocoma_dev
pocoma.v3.pilot.read.username=pocoma_dev_r
```

---

## 4. Procédure d'Amorçage Administratif (`prepare`)

L'amorçage applique Flyway (migrations WRITE V1 à V6 et READ V1), configure la journalisation PostgreSQL, initialise les contrats de routage et pose les privilèges de moindre privilège :

```sh
# 1. Compilation du jar exécutable du Worker
(cd app && ./mvnw -pl runtime-command-consumption-worker -am clean package -DskipTests)

# 2. Exécution de l'opération administrative prepare
python3 -B scripts/v3/worker-dev.py prepare --config /chemin/absolu/runtime.properties
```

**Sortie attendue :**
```text
I5_DEV_STAGE write/configure-l3-identity-logging.sql completed
I5_DEV_STAGE write/bootstrap-l2-routing.sql completed
I5_DEV_STAGE write/grant-l2-lifecycle.sql completed
I5_DEV_STAGE write/grant-l2-discovery.sql completed
I5_DEV_STAGE write/bootstrap-l2-worker-dev.sql completed
I5_DEV_STAGE write/grant-l2-worker-dev.sql completed
I5_DEV_STAGE read/bootstrap-l2-routing.sql completed
I5_DEV_STAGE read/grant-l2-lifecycle.sql completed
I5_DEV_STAGE read/grant-l2-discovery.sql completed
I5_DEV_STAGE read/bootstrap-l2-worker-dev.sql completed
I5_DEV_STAGE read/grant-l2-worker-dev.sql completed
I5_DEV_VALIDATED write
I5_DEV_VALIDATED read
I5_DEV_PREPARED; start/restart never calls prepare
```
Un fichier `runtime.properties.prepared.json` est généré, attestant du succès administratif.

---

## 5. Démarrage et Cycle de Vie du Worker

### 5.1 Démarrage du Worker en arrière-plan

```sh
JAR_PATH="$PWD/app/runtime-command-consumption-worker/target/pocoma-runtime-command-consumption-worker-0.0.1-SNAPSHOT-exec.jar"

java -jar "$JAR_PATH" \
  --spring.profiles.active=v3-consumption-worker-dev \
  --spring.config.additional-location=file:/chemin/absolu/runtime.properties \
  > /chemin/absolu/worker.log 2>&1 &

WORKER_PID=$!
echo "Worker démarré avec PID $WORKER_PID"
```

### 5.2 Contrôle de l'état actif
Vérifier l'apparition des logs de démarrage :
```sh
grep "Started PocomaCommandConsumptionWorkerApplication" /chemin/absolu/worker.log
```

### 5.3 Arrêt propre et qualification du drainage (`stop`)
L'arrêt s'effectue via l'outil DEV en fournissant obligatoirement le PID et le fichier de journal pour certifier le drain physique complet :

```sh
python3 -B scripts/v3/worker-dev.py stop \
  --config /chemin/absolu/runtime.properties \
  --pid $WORKER_PID \
  --log-file /chemin/absolu/worker.log
```

**Statuts de sortie :**
- `Code 0 (I5_DEV_STOPPED)` : Arrêt propre constaté avec callback `I5_WORKER_STOPPED` après drainage complet de tous les slots actifs.
- `Code 3 (I5_DEV_STOP_INCONCLUSIVE)` : Processus arrêté mais callback manquant dans le log.
- `Code 2 (I5_DEV_CRASH)` : Timeout de 105s dépassé ; arrêt forcé par SIGKILL.

---

## 6. Injection d'Intentions et Contrôle de Traitement

### 6.1 Injection d'une demande Registration

Créer un fichier d'intention confidentiel (`chmod 0600 intention-reg.properties`) :
```properties
request-id=a0000001-0000-4000-8000-000000000001
created-at=2026-11-01T10:00:00Z
operation=REGISTRATION
requester.issuer=https://auth.example.com
requester.subject=user-alpha
target.issuer=https://auth.example.com
target.subject=user-alpha
```

Injecter la demande via l'injecteur interne :
```sh
python3 -B scripts/v3/worker-dev.py inject-identity \
  --config /chemin/absolu/runtime.properties \
  --intention-file /chemin/absolu/intention-reg.properties
```
*Sortie :* `L3_DEV_IDENTITY_ADMITTED request=a0000001-0000-4000-8000-000000000001 status=PENDING origin=CONTROLLED_INTERNAL`

### 6.2 Vérification de la complétion et extraction du token

Attendre l'exécution par le Worker :
```sh
python3 -B scripts/v3/worker-dev.py verify-identity \
  --config /chemin/absolu/runtime.properties \
  --request-id a0000001-0000-4000-8000-000000000001 \
  --await-seconds 90
```
*Sortie :* `L3_DEV_IDENTITY_VERIFIED request=... result={outcome=CHANGED, target_version=1}`

Extraire de manière confidentielle le `bindingToken` attribué :
```sh
cat << 'EOF' > own-key.properties
issuer=https://auth.example.com
subject=user-alpha
EOF
chmod 0600 own-key.properties

python3 -B scripts/v3/worker-dev.py identity-token \
  --config /chemin/absolu/runtime.properties \
  --own-key-file own-key.properties \
  --token-output token-alpha.secret
chmod 0600 token-alpha.secret
```

### 6.3 Injection d'un Link vers une seconde identité

Créer l'intention de liaison (`intention-link.properties`) en utilisant le token extrait :
```properties
request-id=a0000001-0000-4000-8000-000000000002
created-at=2026-11-01T10:01:00Z
operation=LINK
requester.issuer=https://auth.example.com
requester.subject=user-alpha
target.issuer=https://github.com
target.subject=dev-alpha
binding-token=CONTENU_DU_TOKEN_ALPHA
```

Injecter et vérifier la complétion :
```sh
python3 -B scripts/v3/worker-dev.py inject-identity --config /chemin/absolu/runtime.properties --intention-file intention-link.properties
python3 -B scripts/v3/worker-dev.py verify-identity --config /chemin/absolu/runtime.properties --request-id a0000001-0000-4000-8000-000000000002
```

---

## 7. Diagnostic Opérationnel et Résolution d'Incidents

### 7.1 Diagnostic des rejets métier (`REJECTED`)
Les rejets métier ne constituent pas des erreurs techniques. Si `verify-identity` rapporte `outcome=REJECTED`, inspecter le motif normatif dans `v3_identity_request_result` :
- `INVALID_BINDING` : Le `bindingToken` fourni est absent, invalide, ou n'appartient pas à l'initiateur.
- `TARGET_ALREADY_BOUND` : La cible est déjà liée (pour une Registration ou un re-Link illicite).
- `TARGET_NOT_BOUND` : La cible est libre ou absente lors d'une tentative de déliaison (`Unlink`).
- `TARGET_BOUND_TO_ANOTHER_USER` : La cible appartient à un utilisateur différent de l'initiateur.

### 7.2 Diagnostic des slots en quarantaine (`QUARANTINED`)
Si une défaillance technique persistante (ex. indisponibilité PostgreSQL) provoque $N=3$ échecs consécutifs sur un slot, celui-ci bascule en statut `QUARANTINED` sans être purgé.
- Constat SQL :
  ```sql
  select consumable_id, state, attempt_count, last_error
  from consumption_slot
  where consumption_id = '2999e75c-272d-5afe-a966-c9364ef021fe'
    and state = 'QUARANTINED';
  ```
- Réactivation administrative ciblée (via le rôle `POCOMA_I5_REACTIVATION_ROLE`) :
  ```sql
  update consumption_slot
  set state = 'AVAILABLE', attempt_count = 0, last_error = null
  where consumption_id = '2999e75c-272d-5afe-a966-c9364ef021fe'
    and consumable_id = 'UUID_DE_LA_DEMANDE'
    and state = 'QUARANTINED';
  ```

### 7.3 Diagnostic UNKNOWN et Fencing
En cas de crash de nœud ou de coupure réseau lors du commit, le bail passe à expiration. La politique de sécurité `UNKNOWN → DRAINING` interdit toute reprise immédiate sans revalidation du token de bail. Le Worker suivant attendra l'expiration physique du bail (`owned_until`) avant toute réacquisition.
