# Pocoma V3 — L7.5 — Rapport de Contre-Audit Documentation, Shareability & DX

- **Date** : 23 novembre 2026
- **Branche PR #12** : `fix/l7-5-ci-visibility-oracle` (PR conservée ouverte jusqu'à arbitrage humain)
- **SHA code exécutable certifié (`CERTIFICATION`)** : `424bde2ae86ae38c24261482480f4134cb019280` (`1 222/1 222 PASS`, `52/52 architecture-tests`)
- **SHA documentation corrigée (base du clone isolé)** : `56d4e21b`
- **CI verte de référence sur code+livrables L7.5** : run [`45180518999`](https://github.com/kartaguez/pocoma/actions/runs/45180518999) sur `398f1781` (`8/8` jobs `success`)

---

## 1. Synthèse des quatre verdicts indépendants

| Dimension | Verdict | Synthèse |
| :--- | :--- | :--- |
| **`INSTALLABLE`** | **`PASS_WITH_RESERVES`** | `doctor` et `start` exécutés sur clone propre isolé (`/tmp/pocoma-dx-audit`) avec fail-closed propre (`PRE-DISK`, `exit 2`, `environment: PRESERVED`) ; `143/143` tests CLI Python `PASS` ; étapes 2–9 (`start` → `READY` → OIDC → `CreatePot` → `READ` → Bruno → `stop`/`destroy` et mode `NOOP`) **non exécutées** faute d'espace disque libre sur l'hôte (`1,15 GiB` disponible < `2,0 GiB` requis par `PRE-DISK`). |
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
| [`docs/architecture/README.md`](../architecture/README.md) | Mentionnait `9-container topology` et `42 modules`. | Corrigé en `10-container T1A + Core NATS topology (9 in --wakeup-mode noop)` et `43 modules`. |
| [`docs/guides/local-development.md`](../guides/local-development.md) | Mentionnait `9-container playground` pour `reload`. | Corrigé en `10-container playground (or 9 in --wakeup-mode noop)`. |
| [`docs/operations/troubleshooting.md`](../operations/troubleshooting.md), [`docs/operations/recovery.md`](../operations/recovery.md) | Mentionnaient `All 9 containers`. | Corrigé en `All 10 containers (or 9 in --wakeup-mode noop)`. |
| [`docs/v3/L7_I1_Certification_Coverage_Matrix.csv`](L7_I1_Certification_Coverage_Matrix.csv) | Ligne 71 (`A10-I`) comportait des virgules non échappées dans `Rapprochement L7 Final` (`len=24` au lieu de `22`). | Champ `Rapprochement L7 Final` mis entre guillemets ; contrôle Python : `Header len: 22 Bad rows: []`. |
| [`docs/testing/evidence/l7-5-counter-audit/qualification-proof.json`](../testing/evidence/l7-5-counter-audit/qualification-proof.json), [`docs/v3/L7_5_Final_Closure_Dossier.md`](L7_5_Final_Closure_Dossier.md) | Provenance CI pointait encore sur `45157493230` (`9755be7f`). | Provenance mise à jour avec le run vert [`45180518999`](https://github.com/kartaguez/pocoma/actions/runs/45180518999) sur `398f1781` (`8/8` jobs `success`) et `manifest.sha256` recalculé (`OK`). |

Les rapports historiques (`docs/releases/v3.0.0-l7-local.md`, `docs/v3/L7_Final_Global_Qualification_Dossier.md`, `docs/archive/**`) n'ont pas été réécrits.

---

## 3. Qualification réelle d'onboarding (clone propre isolé)

Exécutée depuis un clone propre et isolé de `56d4e21b` dans `/tmp/pocoma-dx-audit` (supprimé après audit ; aucune instance Docker existante modifiée ou supprimée) :

| Étape | Commande / Action | Résultat observé | Statut |
| :--- | :--- | :--- | :--- |
| 1. Prérequis | `./pocoma-local doctor` | `exit 2` (`NOT_READY`) : Python `3.14.7`, `6/6` images Docker en cache, `dockerMemoryMiB: 7934` (`memorySufficient: true`), `buildCached: false`, `brunoCliInstalled: false`, **`diskSufficient: false`** (`PRE-DISK: requiredBytes=2147483648, availableBytes=1238921216`). | **EXECUTED (FAIL-CLOSED `PRE-DISK`)** |
| 2. Démarrage T1A/NATS | `./pocoma-local start --instance dx-audit` | `exit 2` (`NOT_READY`), `control: "PRE-DISK: requiredBytes=2147483648, availableBytes=1238474752"`, `environment: "PRESERVED"`. | **EXECUTED (FAIL-CLOSED `PRE-DISK`)** |
| 3–9. `READY`, OIDC, `CreatePot`, `CommandResult`, `READ`, Bruno, `stop`/`start`/`destroy`, mode `NOOP` | Non exécutées | Bloquées par l'espace disque libre de l'hôte (`1,2 Gi` libre sur `/` < `2,0 GiB` requis par `PRE-DISK`). | **NOT_EXECUTED (BLOCKED BY HOST DISK)** |

> [!NOTE]
> Les prérequis matériels et contrôles `PRE-DISK` fonctionnent exactement comme spécifié (`environment: PRESERVED`, zéro effet de bord). Aucune étape non exécutée n'est déclarée `PASS`.

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
4. `git diff --check` → **`0` anomalie**.
5. Clone propre `/tmp/pocoma-dx-audit` : `./pocoma-local doctor`, `./pocoma-local start --instance dx-audit`, `verify.py FAST identity` (`53/53 PASS`), `./mvnw -pl domain-pot -am test` (`47/47 PASS`).
