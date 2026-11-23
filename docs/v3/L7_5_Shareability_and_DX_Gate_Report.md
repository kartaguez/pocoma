# Pocoma V3 — L7.5 — Rapport de Contre-Audit Documentation, Shareability & DX

- **Date** : 23 novembre 2026
- **Branche PR #12** : `fix/l7-5-ci-visibility-oracle` (PR conservée ouverte jusqu'à arbitrage humain)
- **SHA code exécutable certifié (`CERTIFICATION`)** : `424bde2ae86ae38c24261482480f4134cb019280` (`1 222/1 222 PASS`, `52/52 architecture-tests`)
- **SHA documentation qualifié (base du clone isolé)** : `cd8172f4e509c5fefcec6fa967eb2cf038beed42`
- **CI verte de référence sur code+livrables L7.5** : run [`45180518999`](https://github.com/kartaguez/pocoma/actions/runs/45180518999) sur `398f1781` (`8/8` jobs `success`), puis CI sur le HEAD final de la PR #12

---

## 1. Synthèse des quatre verdicts indépendants

| Dimension | Verdict | Synthèse |
| :--- | :--- | :--- |
| **`INSTALLABLE`** | **`PASS`** | Parcours complet T1A/NATS exécuté de bout en bout sur clone propre isolé (`/tmp/pocoma-dx-qualif`, instance dédiée `dx-qualif` sur `cd8172f4`) : `doctor` (`READY`, `5,28 Go` libres), `start` (10 conteneurs, T1A, Core NATS `CONNECTED`, 5 JVMs `PASS`, `READY`), OIDC PKCE S256 (`third`), `Registration`, `CreatePot` sans `potId` client (`202`), `CommandResult` (`COMPLETED`, `potId` attribué par WRITE), projection `READ` (`200 OK`), Bruno (`BRUNO_PASS`), `stop` / `start` / `status` (`READY`), `destroy --dry-run` et `destroy` (`DESTROYED`). |
| **`UNDERSTANDABLE`** | **`PASS`** | Parcours 30 min (`README.md`), séparation WRITE/READ + T1A + Core NATS (`overview.md`, `runtimes.md`), taxonomie des 43 modules (`modules.md`), et séparation claire des preuves historiques vs références actives. |
| **`READABLE`** | **`PASS`** | Contrats `/api/v3` (`first-steps.md`, `reference/api.md`, `guides/commands.md`) alignés sur l'attribution serveur des IDs (`CreatePot`, `AddShareholders`, `CreateExpense` → `createdResources`), date d'invitation future (`2027-12-31T12:00:00Z`), intégrité CSV `A10-I` rétablie (`522/522` lignes à `22` colonnes), `git diff --check` propre. |
| **`CONTRIBUTABLE`** | **`PASS`** | Frontières `domain`/`contract`/`port`/`engine`/`orchestrator`/`supra`/`infra`/`compose`/`runtime` explicites, politique de tranches claire (`CONTRIBUTING.md`, `Reactor_Verification_Policy.md`), contribution simulée vérifiée en `3,30 s` (`verify.py FAST identity`, `53/53`) et `2,10 s` (`-pl domain-pot -am test`, `47/47`). |

---

## 2. Audit documentaire et défauts corrigés (`56d4e21b`)

### 2.1 Conformité avec le code actif (`CommandAdmissionInput.java`, `scripts/bruno-v3/lib/i2-admission.js`, `app/pom.xml`)

| Fichier | Défaut constaté | Correction appliquée |
| :--- | :--- | :--- |
| [`docs/getting-started/first-steps.md`](../getting-started/first-steps.md) | Demandait de générer un `POT_ID` côté client et d'envoyer `"potId": "$POT_ID"` dans `CreatePot` ; omettait `createdResources.potId` dans `CommandResult`. | Suppression de la génération client de `POT_ID` ; indication explicite que `potId` est omis à l'admission et récupéré dans `createdResources.potId` (ou `potId`) de `GET /commands/{id}/result`. |
| [`docs/reference/api.md`](../reference/api.md) | Enveloppe `POST /commands` imposait `potId` pour `CreatePot` ; `GET /commands/{id}/result` omettait `createdResources` ; `AddShareholders` et `CreateExpense` montraient un champ client `id`. | `potId` documenté comme omis pour `CreatePot` et requis pour les autres opérations ; `createdResources` ajouté ; champs `id` retirés de `AddShareholders` et `CreateExpense`. |
| [`docs/guides/commands.md`](../guides/commands.md) | Mêmes erreurs `potId`/`id` sur `CreatePot`, `AddShareholders`, `CreateExpense` ; exemple `CreateInvitation` daté du `2026-11-20T12:00:00Z` (expiré au 23 novembre 2026). | Suppression des IDs clients, ajout de `createdResources`, date d'exemple portée à `2027-12-31T12:00:00Z`. |
| [`README.md`](../../README.md) | Affichait les anciens compteurs L7 (`1 173/1 173`, `50/50`) et `L7_LOCAL_CERTIFIED_WITH_RESERVES`. | Actualisation vers `GO_LOCAL_WITH_RESERVES` (L7.5 : `1 222/1 222`, `52/52 architecture-tests`, `143/143` CLI Python) avec lien vers [`L7_5_Final_Closure_Dossier.md`](L7_5_Final_Closure_Dossier.md) et mention explicite du dossier L7 comme référence historique. |
| [`CONTRIBUTING.md`](../../CONTRIBUTING.md) | Mentionnait `42 Maven modules` ; commande Python ne listait que 7 fichiers `test_*.py` sur 16. | Corrigé en `43 child modules (44 projects with the aggregator)` et `python3 -B -m unittest discover -s scripts/local -p "test_*.py"`. |
| [`docs/architecture/README.md`](../architecture/README.md), [`docs/README.md`](../README.md) | Mentionnaient `9-container topology` et `42 modules`. | Corrigé en `10-container T1A + Core NATS topology (9 in --wakeup-mode noop)` et `43 modules` (`cd8172f4`). |
| [`docs/guides/local-development.md`](../guides/local-development.md) | Mentionnait `9-container playground` pour `reload`. | Corrigé en `10-container playground (or 9 in --wakeup-mode noop)`. |
| [`docs/operations/troubleshooting.md`](../operations/troubleshooting.md), [`docs/operations/recovery.md`](../operations/recovery.md) | Mentionnaient `All 9 containers`. | Corrigé en `All 10 containers (or 9 in --wakeup-mode noop)`. |
| [`docs/v3/L7_I1_Certification_Coverage_Matrix.csv`](L7_I1_Certification_Coverage_Matrix.csv) | Ligne 71 (`A10-I`) comportait des virgules non échappées dans `Rapprochement L7 Final` (`len=24` au lieu de `22`). | Champ `Rapprochement L7 Final` mis entre guillemets ; contrôle Python : `Header len: 22 Bad rows: []`. |
| [`docs/testing/evidence/l7-5-counter-audit/qualification-proof.json`](../testing/evidence/l7-5-counter-audit/qualification-proof.json), [`docs/v3/L7_5_Final_Closure_Dossier.md`](L7_5_Final_Closure_Dossier.md) | Provenance CI pointait encore sur `45157493230` (`9755be7f`). | Provenance mise à jour avec le run vert [`45180518999`](https://github.com/kartaguez/pocoma/actions/runs/45180518999) sur `398f1781` (`8/8` jobs `success`) et `manifest.sha256` recalculé (`OK`). |

Les rapports historiques (`docs/releases/v3.0.0-l7-local.md`, `docs/v3/L7_Final_Global_Qualification_Dossier.md`, `docs/archive/**`) n'ont pas été réécrits.

---

## 3. Qualification réelle d'onboarding (clone propre isolé)

Exécutée de bout en bout depuis un clone propre et isolé de `cd8172f4` dans `/tmp/pocoma-dx-qualif` (instance dédiée `dx-qualif`, après libération d'un répertoire temporaire `/private/tmp/pocoma-c3-gates.rkf5oS` sans toucher à aucun volume ou instance Docker existant ; clone supprimé après destruction contrôlée) :

| Étape | Commande / Action | Résultat observé | Statut |
| :--- | :--- | :--- | :--- |
| 1. Prérequis | `./pocoma-local doctor` | `exit 0` (`READY`) : Python `3.14.7`, `6/6` images Docker en cache, `dockerMemoryMiB: 7934`, `freeDiskBytes: 5280493568` (`5,28 Go` ≥ `2,0 GiB`), `diskSufficient: true`. | **PASS** |
| 2. Démarrage T1A/NATS | `./pocoma-local start --instance dx-qualif` | `exit 0`, run `1795447872-b94b9da1` (`~53 s`), `pocoma_write` & `pocoma_read` initialisés, 10 conteneurs démarrés. | **PASS** |
| 3. `READY` & 5 runtimes | `./pocoma-local status --instance dx-qualif` | `state: READY`, `operationalState: OPERATIONAL`, `qualificationState: QUALIFIED`, 5 JVMs `PASS`, `wakeup.topology: T1A`, `wakeup.effectiveMode: CONNECTED` (`connections: 4`, `subscriptions: 88`). | **PASS** |
| 4. OIDC & `Registration` | `auth.login(ISSUER, "third", "pocoma")` → `GET /me/binding` → `POST /identity/requests` (`Registration`) | Login PKCE S256 OK ; `404` initial → `202` (`4b624939-48d9-4a0c-9edf-5cab311c7e15`) → `COMPLETED` (`targetVersion: 1`) → `bindingToken` actif (`euiVersion: 1`). | **PASS** |
| 5. `CreatePot` sans `potId` client | `POST /api/v3/commands` (`operation: CreatePot`, `observedVersion: 0`, sans champ `potId`) | `202 Accepted` (`requestId: fbd15354-8ed0-4509-870e-89bf1f45430d`). | **PASS** |
| 6. `CommandResult` & ID attribué | `GET /api/v3/commands/fbd15354-.../result` | `200 OK`, `outcome: COMPLETED`, `resultingPotVersion: 1`, `createdResources.potId: 14ff1ee8-00ad-44f5-a67f-7cd9c9501fd8`. | **PASS** |
| 7. Projection `READ` | `GET /api/v3/pots/14ff1ee8-...?minVersion=1` (+ `X-Pocoma-Binding-Token`) | `200 OK`, `potVersion: 1`, `label: "DX Audit Trip"`. | **PASS** |
| 8. Suite Bruno E2E | `./pocoma-local bruno --instance dx-qualif` | `state: BRUNO_PASS`, `exit 0` (evidence `1795447970-db561018`). | **PASS** |
| 9. `stop` / `start` / `destroy` | `stop` → `start` → `status` → `destroy --dry-run` → `destroy --confirm DESTROY:dx-qualif` | `stop` (`STOPPED`, `forcedKill: []`, `volumes: PRESERVED`) → `start`/`status` (`READY`, `canaryProof.version: 3`, `CONNECTED`) → `destroy` (`DESTROYED`, `removedOwnedVolumes: ["pocoma-lb-dx-qualif-e203f823-data"]`, volumes non possédés préservés). | **PASS** |

---

## 4. Audit de contributabilité (simulation réelle)

1. **Repérage architectural** : `README.md` → `docs/architecture/modules.md` → `docs/guides/adding-read-projections.md` permettent d'identifier immédiatement les 43 modules (`domain-*`, `contract-*`, `port-*`, `engine-*`, `orchestrator-consumption`, `supra-*`, `infra-*`, `compose-*`, `runtime-*`) et la table « Where Should I Place New Code? ».
2. **Politique de vérification par tranches** : `CONTRIBUTING.md` et `docs/testing/Reactor_Verification_Policy.md` fournissent dès le TL;DR les 4 tranches canoniques et `verify.py FAST identity`.
3. **Contribution simulée sur clone propre (`/tmp/pocoma-dx-audit`)** :
   - `python3 -B scripts/testing/verify.py FAST identity` → **`PASS` (`53/53`, `3,30 s`)**.
   - Ajout d'un test JUnit dans `app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/PotVisibilityRulesTest.java` et exécution de `./mvnw --batch-mode --no-transfer-progress -pl domain-pot -am test` → **`BUILD SUCCESS` (`47/47`, `2,097 s`)**.

---

## 5. Limites de plateforme et dépendances réseau/cache

- **Plateformes** : `./pocoma-local` est qualifié sur **macOS (Apple Silicon / ARM64)** avec Docker Desktop (`≥ 8 GiB` RAM, `≥ 2 GiB` disque libre) ; la CI Maven + Testcontainers tourne sur **Linux (`ubuntu-24.04`)**. L'usage local de `./pocoma-local` sous Linux et Windows (WSL2) n'est pas encore qualifié indépendamment.
- **Dépendances réseau / cache** : sur un poste vierge, le premier démarrage requiert un accès réseau vers Maven Central (`~/.m2`), les registres Docker (~1,5–2 Go pour les 6 images dont `nats:2.15.3-alpine`) et `npm` (installation de Bruno CLI `4.3.0` dans `scripts/bruno-v3/`).

---

## 6. Vérifications exécutées sur ce lot documentaire

Conformément à la section 34 de `Reactor_Verification_Policy.md` (modifications exclusivement `Markdown`, `CSV` et `JSON` de preuve), la certification Maven globale de `1 222` tests n'a pas été rejouée :

1. `python3 -B -m unittest discover -s scripts/local -p "test_*.py"` → **`143/143 PASS`** (`2,246 s`).
2. Contrôle CSV (`docs/v3/L7_I1_Certification_Coverage_Matrix.csv`) → **`Header len: 22, Bad rows: []`** (`522/522` lignes valides).
3. `(cd docs/testing/evidence/l7-5-counter-audit && shasum -a 256 -c manifest.sha256)` → **`qualification-proof.json: OK`**.
4. Contrôle des liens relatifs sur `28` fichiers de documentation publique → **`0` lien cassé**.
5. `git diff --check` → **`0` anomalie**.
6. Clone propre `/tmp/pocoma-dx-qualif` (`cd8172f4`) : parcours complet `doctor` → `start` → `status` (`READY`, T1A/NATS) → OIDC PKCE → `Registration` → `CreatePot` (sans `potId` client) → `CommandResult` → projection `READ` → `bruno` (`BRUNO_PASS`) → `stop` → `start` → `status` → `destroy --dry-run` → `destroy` (`DESTROYED`), plus `verify.py FAST identity` (`53/53 PASS`) et `-pl domain-pot -am test` (`47/47 PASS`).
