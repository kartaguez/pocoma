# L7-LB / S7 — Rapport de productisation de la documentation

- **Mandat** : `GO_L7_LB_S7_DOCUMENTATION_PRODUCTIZATION`
- **Date** : 15 novembre 2026
- **Branche** : `feat/l7-lb-local-playground`
- **SHA initial** : `4d889ec6`

## Verdict S7 et recommandation `L7-LB`

- **Verdict S7** : **`L7_LB_S7_QUALIFIED`**
- **Recommandation `L7-LB`** : **`L7_LB_CLOSURE_RECOMMENDED`**

---

## 1. Inventaire documentaire initial

L'audit initial du dépôt au SHA `4d889ec6` a recensé sous `docs/` (hors fichiers système `.DS_Store`) :

- Racine `docs/` : `5` fichiers (`README.md`, `Documentation_Rationalization_Audit.md`, `Documentation_Truth_Validation.md`, `projection-workers.md`, `use-case-families.md`)
- `docs/getting-started/` : `2` fichiers (`prerequisites.md`, `quickstart.md`)
- `docs/architecture/` : `54` fichiers (`4` pages produit S4 + `50` études, audits et V2 historiques)
- `docs/operations/` : `12` fichiers (`2` pages produit S4 + `4` runbooks historiques/dev-bench + `6` scripts SQL sous `sql/`)
- `docs/reference/` : `2` fichiers (`commands.md`, `configuration.md`)
- `docs/development/` : `1` fichier (`ci.md`)
- `docs/testing/` : `11` fichiers racine (dont `Reactor_Verification_Policy.md`, `Evidence_*.json`) + `4 089` fichiers sous `docs/testing/evidence/`
- `docs/archive/` : `7` fichiers racine + `20` fichiers sous `docs/archive/v3-sql-migrations/`
- `docs/debts/` : `39` fichiers
- `docs/plans/` : `53` fichiers
- `docs/steps/` : `51` fichiers
- `docs/product/` : `1` fichier (`Functional_Model.md`)
- `docs/guarantees/` : `1` fichier (`System_Guarantees.md`)
- `docs/v3/` : `260` fichiers racine (specs `SPEC-01..10`, rapports L0–L7, manifestes JSON) + `100` fichiers sous `docs/v3/evidence/`

Hors `docs/`, les documents racine et de scripts recensés incluent : `README.md`, `AGENTS.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, `LICENSE`, `NOTICE`, `app/HELP.md`, `graft/INDEX.md`, et les README de `scripts/bruno/`, `scripts/bruno-v3/`, `scripts/bruno-playground/`, `scripts/k6/`, `scripts/performance/`.

---

## 2. Classification

| Catégorie | Périmètre classifié | Disposition appliquée |
| :--- | :--- | :--- |
| **`PRODUCT`** | `README.md`, `docs/README.md`, `docs/getting-started/*`, `docs/guides/*`, `docs/architecture/{README,overview,runtimes,persistence,security}.md`, `docs/reference/*`, `docs/operations/{README,troubleshooting,recovery}.md` | Parcours principal autonome, réécrit et consolidé sans dépendance à l'historique L1–L7 |
| **`INTERNAL_ACTIVE`** | `AGENTS.md`, `docs/testing/Reactor_Verification_Policy.md`, `docs/development/ci.md`, `docs/v3/L7_Target_Modular_Architecture_and_Migration_Plan.md`, `docs/operations/sql/*` | Conservés à leurs emplacements contractuels et indexés depuis `docs/internal/README.md` |
| **`HISTORICAL`** | `docs/plans/*`, `docs/steps/*`, `docs/debts/*`, `docs/architecture/*` (études datées & `Architecture.md`), `docs/Documentation_*.md`, `docs/projection-workers.md`, `docs/use-case-families.md`, `docs/operations/{cmd-start-runtimes,event-* ,latest-*}.md`, `docs/v3/*` (hors preuves) | Déplacés vers `docs/internal/` via `git mv` pour les chemins non contractuels ; maintenus sur place pour `docs/v3/*` référencés par les tests/scripts, et indexés dans `docs/internal/README.md` |
| **`EVIDENCE`** | `docs/testing/evidence/*`, `docs/v3/evidence/*`, `docs/testing/Evidence_*.json` | Conservés intacts à leurs emplacements contractuels |
| **`ARCHIVE`** | `docs/archive/*`, `docs/archive/v3-sql-migrations/*`, `docs/product/*` → `docs/internal/v2/product/`, `docs/guarantees/*` → `docs/internal/v2/guarantees/` | Conservés sans suppression ; archives V2 déplacées dans `docs/internal/v2/` |

### Chemins contractuels vérifiés avant déplacement

`git grep` sur `AGENTS.md`, `.github/`, `app/`, `scripts/`, `docker/`, `pocoma-local` a confirmé `77` chemins `docs/...` existants et référencés par le code ou les scripts (notamment `docs/testing/Reactor_Verification_Policy.md`, `docs/testing/evidence/*`, `docs/archive/v3-sql-migrations/*`, `docs/v3/L7_Target_Modular_Architecture_and_Migration_Plan.md`, `docs/v3/L*_Proof_Manifest.json`, `docs/v3/L7_Local_Playground_Bruno.md`, `docs/operations/sql/*`). **Aucun** de ces chemins contractuels n'a été déplacé.

---

## 3. Structure finale

```text
README.md                               # Point d'entrée produit concis
docs/
  README.md                             # Portail produit principal (sans historique L1–L7)
  getting-started/
    README.md
    installation.md                     # Prérequis, démarrage, READY, arrêt, suppression, 1er dépannage
    first-steps.md                      # Parcours autonome : start -> auth/binding -> command -> result -> read
  guides/
    README.md
    authentication.md                   # OIDC Keycloak PKCE S256, Registration, Unlink, bindingToken
    commands.md                         # Admission 202, Idempotency-Key, results, payloads métier
    queries.md                          # Lecture READ, minVersion, historique, soldes, 404 opaque
    local-development.md                # Instances --instance, Bruno GUI/CLI, Grafana, slices Maven
  architecture/
    README.md
    overview.md                         # Concepts métier, CQRS, 5 runtimes, 9 conteneurs
    runtimes.md                         # Détails 5 JVM, 36 partitions, baux 30s, renewBatch, fencing
    persistence.md                      # pocoma_write/read, baselines V1, bootstrap & coordination
    security.md                         # OIDC, pré-sémaphore Q=8, idempotence HMAC, secrets locaux
  reference/
    README.md
    cli.md                              # ./pocoma-local (9 sous-commandes, options, codes sortie)
    configuration.md                    # Profils, pools Hikari, baux, polling (ms), conteneurs
    api.md                              # 12 routes /api/v3, 13 opérations, rationnels, erreurs HTTP
  operations/
    README.md
    troubleshooting.md                  # operationalState/qualificationState, PRE-*/DB-*/KEYCLOAK_*/GRAFANA-*, limites S6
    recovery.md                         # Cycle de vie, reprise arrêt brutal (270s), coupure PostgreSQL
    sql/                                # Conservé (référencé par scripts/operations/)
  internal/
    README.md                           # Point d'entrée secondaire (politiques, specs, rapports, preuves, archives)
    L7_LB_S7_Documentation_Productization_Report.md
    Documentation_Rationalization_Audit.md
    Documentation_Truth_Validation.md
    projection-workers.md
    use-case-families.md
    architecture/                       # 50 documents historiques/études déplacés
    debts/                              # 39 fichiers de dettes techniques déplacés
    operations/                         # 4 runbooks dev-bench/cutovers déplacés
    plans/                              # 53 plans historiques déplacés
    steps/                              # 51 fichiers de steps historiques déplacés
    v2/                                 # Functional_Model.md & System_Guarantees.md déplacés
  archive/                              # Index mis à jour + v3-sql-migrations/ conservé (contractuel)
  development/                          # ci.md conservé
  testing/                              # Reactor_Verification_Policy.md & evidence/ conservés (contractuel)
  v3/                                   # Specs, rapports & evidence/ conservés (contractuel) et indexés dans internal/README.md
```

---

## 4. Manifeste des déplacements (`git mv`)

Au total, **`203` fichiers suivis** ont été déplacés proprement avec `git mv` (aucune suppression) :

| Ancien chemin | Nouveau chemin | Volume |
| :--- | :--- | :---: |
| `docs/getting-started/prerequisites.md` | `docs/getting-started/installation.md` | `1` |
| `docs/getting-started/quickstart.md` | `docs/getting-started/first-steps.md` | `1` |
| `docs/reference/commands.md` | `docs/reference/cli.md` | `1` |
| `docs/operations/local-instances.md` | `docs/operations/recovery.md` | `1` |
| `docs/Documentation_Rationalization_Audit.md` | `docs/internal/Documentation_Rationalization_Audit.md` | `1` |
| `docs/Documentation_Truth_Validation.md` | `docs/internal/Documentation_Truth_Validation.md` | `1` |
| `docs/projection-workers.md` | `docs/internal/projection-workers.md` | `1` |
| `docs/use-case-families.md` | `docs/internal/use-case-families.md` | `1` |
| `docs/plans/*` | `docs/internal/plans/*` | `53` |
| `docs/steps/*` | `docs/internal/steps/*` | `51` |
| `docs/debts/*` | `docs/internal/debts/*` | `39` |
| `docs/product/*` | `docs/internal/v2/product/*` | `1` |
| `docs/guarantees/*` | `docs/internal/v2/guarantees/*` | `1` |
| `docs/operations/{cmd-start-runtimes,event-consumption-cutover,event-sequencing-runbook,latest-known-version-cutover}.md` | `docs/internal/operations/*` | `4` |
| `docs/architecture/*` *(hors `overview.md`, `runtimes.md`, `persistence.md`, `security.md`)* | `docs/internal/architecture/*` | `47` |

---

## 5. Documents consolidés, créés ou réécrits

- **Réécrits / consolidés** :
  - `README.md` (accueil produit concis, sans historique L1–L7)
  - `docs/README.md` (portail produit épuré ; historique renvoyé vers `docs/internal/README.md`)
  - `docs/getting-started/installation.md` & `docs/getting-started/first-steps.md`
  - `docs/architecture/overview.md` (ajout des concepts métier et séparation progressive WRITE/READ)
  - `docs/architecture/runtimes.md` (alignement S6 : `pocoma.v3.http.admission.capacity=4`, intervalles de polling en **millisecondes**)
  - `docs/architecture/persistence.md` & `docs/architecture/security.md` (liens autonomes vers la doc produit)
  - `docs/reference/cli.md`
  - `docs/operations/troubleshooting.md` (états `operationalState`/`qualificationState`, limites connues S6 `25 cmd/s` vs `50 cmd/s`)
  - `docs/operations/recovery.md` (procédure S6 de reprise après coupure PostgreSQL, `draining`, `36/36` nouveaux `owner_token`, budget `270 s`)
  - `docs/archive/README.md` (liens mis à jour vers `docs/internal/`)
- **Créés** :
  - `docs/getting-started/README.md`
  - `docs/guides/README.md`, `authentication.md`, `commands.md`, `queries.md`, `local-development.md`
  - `docs/architecture/README.md`
  - `docs/reference/README.md`, `api.md`
  - `docs/operations/README.md`
  - `docs/internal/README.md` et ce rapport `docs/internal/L7_LB_S7_Documentation_Productization_Report.md`

---

## 6. Liens et contrôles vérifiés

| Contrôle | Périmètre | Résultat |
| :--- | :--- | :--- |
| Liens Markdown produit | `README.md`, `docs/README.md`, `docs/internal/README.md`, `getting-started/*`, `guides/*`, `architecture/*`, `reference/*`, `operations/*` | `153/153` liens internes valides après création de ce rapport (`0` lien cassé) |
| Chemins contractuels | `git grep` sur `AGENTS.md`, `app/`, `scripts/` | `77/77` chemins réels présents (`0` manquant) |
| Parité CLI | `./pocoma-local --help` | Code `0` ; 9 sous-commandes et 5 options conformes à `docs/reference/cli.md` |
| Tests CLI Python | `PYTHONPATH=scripts/local python3 -B -m unittest discover -s scripts/local -p 'test_*.py'` | `129/129` tests PASS en `2.342 s` |
| Hygiène Git | `git diff --check` | Code `0` ; aucune modification sous `app/`, `scripts/`, `docker/` |

---

## 7. Résultats des quatre parcours (Test du nouveau développeur)

| Parcours | Chemin depuis `README.md` | Clics | Documents | Ambiguïtés / Manques | Consultation `docs/internal/` |
| :--- | :--- | :---: | :---: | :---: | :---: |
| **A — Installation** | `README.md` → `docs/getting-started/installation.md` | `1` | `1` | `0` | Non |
| **B — Première utilisation** | `README.md` → `docs/getting-started/first-steps.md` | `1` | `1` | `0` | Non |
| **C — Compréhension** | `README.md` → `docs/architecture/overview.md` → `runtimes.md` | `2` | `2` | `0` | Non |
| **D — Exploitation** | `README.md` → `docs/operations/README.md` → `troubleshooting.md` & `recovery.md` | `2` | `3` | `0` | Non |

Tous les parcours essentiels sont complets et accessibles en **1 à 2 clics** depuis `README.md` (cible ≤ 3 clics).

---

## 8. Réserves restantes

1. **Aucune exécution dynamique Docker/Maven supplémentaire en S7** : conformément au mandat (§8.3) et à `docs/testing/Reactor_Verification_Policy.md`, les preuves fonctionnelles et de performance S5/S6 existantes sont réutilisées sans relancer la stack complète ni le réacteur Maven.
2. **Identifiants techniques `v3` conservés** : les routes `/api/v3`, schémas SQL `v3_write`/`v3_read` et profils Spring conservent leur nommage technique pour préserver la compatibilité des instances et contrats existants.

---

## 9. SHA final et rapport de politique de vérification

```text
Start HEAD: 4d889ec6
Expected production impact: NONE (scope strictement documentaire : README.md et docs/)
Observed production impact: NONE (aucune modification sous app/, scripts/, docker/)
Declared slices: NONE (productisation documentaire uniquement)
Verification commands executed:
- ./pocoma-local --help -> PASS (exit 0)
- PYTHONPATH=scripts/local python3 -B -m unittest discover -s scripts/local -p 'test_*.py' -> PASS (129/129)
- Script Python de vérification liens Markdown & chemins contractuels -> PASS (153/153 liens produit, 77/77 chemins contractuels)
- git diff --check -> PASS (exit 0)
Unexpected slice crossings: NONE
Global architecture gate: NOT REQUIRED
Full reactor: NOT REQUIRED
Database verification: NOT REQUIRED
```

- **SHA final** : commit publié sur `feat/l7-lb-local-playground` portant la livraison S7.

---

## 10. Recommandation de clôture `L7-LB`

- **Verdict S7** : **`L7_LB_S7_QUALIFIED`**
- **Recommandation** : **`L7_LB_CLOSURE_RECOMMENDED`** (pour proposition d'intégration dans `main` selon décision humaine).
