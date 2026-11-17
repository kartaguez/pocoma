# L7-LB/S4 — Rapport de stabilisation documentaire et distribution developer-friendly

Date : **15 novembre 2026**
Mandat : `GO_L7_LB_S4_PRODUCT_DOCUMENTATION`
Branche : `feat/l7-lb-local-playground`

**Verdict : `L7_LB_S4_DOCUMENTATION_QUALIFIED_WITH_RESERVATIONS`**

> La distribution locale Pocoma dispose désormais d'une documentation produit structurée en français permettant à un développeur de comprendre le système, vérifier ses prérequis, démarrer une instance locale avec `./pocoma-local`, contrôler `READY` et le canari, exécuter une première opération via Bruno, diagnostiquer les incidents courants et gérer la conservation ou la suppression des données, sans connaissance préalable de l'historique L0–L7. Aucun fichier sous `app/`, `scripts/`, `docker/` ou `.pocoma-local/` n'a été modifié afin de préserver les empreintes d'instance (`demo`) et le périmètre purement documentaire de S4. Le verdict reste `QUALIFIED_WITH_RESERVATIONS` en raison des renommages techniques `v3` délibérément différés, des liens V2 préexistants vers d'anciens modules dans deux documents historiques signalés par bandeau, et de l'absence d'exécution dynamique (`start`/`bruno`/`./mvnw test`) dans ce mandat purement statique. `S4` n'engage pas `S5` et ne prononce pas la clôture de `L7-LB`.

---

## 1. SHA initial

- **SHA initial** : `16dada4430d594b12e8f2d9478d71835dc259eb9` (copie de travail propre sur `feat/l7-lb-local-playground`).

---

## 2. Structure documentaire finale

```text
README.md                               # Point d'entrée produit concis (rôle, topologie, prérequis, quickstart CLI, liens)
docs/
  README.md                             # Index structuré produit en tête + section historique L1-L7 & V2 préservée en dessous
  getting-started/
    prerequisites.md                    # Outils (toolchain.json), limites Docker, variables/fichiers Maven interdits
    quickstart.md                       # Parcours complet instance neuve & comportement sur instance existante
  architecture/
    overview.md                         # CQRS WRITE/READ, topologie 9 conteneurs / 5 JVM, observabilité
    runtimes.md                         # Détails des 5 JVM, Q=8 pré-sémaphore, worker COMMAND JDBC pur, baux 30s + renewBatch
    persistence.md                      # Bases pocoma_write/pocoma_read, baselines V1 actives, isolation V2/V3, refus bootstrap
    security.md                         # Keycloak PKCE S256, Binding, idempotence, secrets locaux & rédaction journaux
  operations/
    local-instances.md                  # Cycle de vie pocoma-local, .pocoma-local/, verrous, upgrade-grafana, budget 270s
    troubleshooting.md                  # Codes sortie 0..4/130, PRE-*, INCOMPATIBLE-*, KEYCLOAK_*, GRAFANA-*, DEGRADED
  reference/
    commands.md                         # Syntaxe exacte ./pocoma-local --help, 9 sous-commandes, options, slices Maven
    configuration.md                    # Profils/pools des 5 runtimes, paramètres baux/polling, limites conteneurs, noms V3 conservés
  archive/
    README.md                           # Index explicite des archives V2, V3 (19 SQL) et migrations test V2 (45 SQL)
    v3-sql-migrations/                  # Inchangé (19 migrations V3 archivées + MANIFEST.json)
  product/Functional_Model.md           # Annoté par bandeau historique V2 (/api/v1)
  architecture/Architecture.md          # Annoté par bandeau d'orientation vers overview.md / runtimes.md
  guarantees/System_Guarantees.md       # Annoté par bandeau historique V2
  operations/cmd-start-runtimes.md      # Annoté comme documentation de câblage manuel / dev-bench
  testing/Functional_Local_Stack.md     # Annoté comme fixture de test fonctionnel monolithique
  v3/                                   # Références applicables (Public API, Bruno, Observability, Keycloak, Recovery, Reservations) + rapports historiques conservés
    L7_LB_S4_Product_Documentation_Report.md
```

---

## 3. Documents créés

1. [`docs/getting-started/prerequisites.md`](../getting-started/prerequisites.md)
2. [`docs/getting-started/quickstart.md`](../getting-started/quickstart.md)
3. [`docs/architecture/overview.md`](../architecture/overview.md)
4. [`docs/architecture/runtimes.md`](../architecture/runtimes.md)
5. [`docs/architecture/persistence.md`](../architecture/persistence.md)
6. [`docs/architecture/security.md`](../architecture/security.md)
7. [`docs/operations/local-instances.md`](../operations/local-instances.md)
8. [`docs/operations/troubleshooting.md`](../operations/troubleshooting.md)
9. [`docs/reference/commands.md`](../reference/commands.md)
10. [`docs/reference/configuration.md`](../reference/configuration.md)
11. [`docs/archive/README.md`](../archive/README.md)
12. [`docs/v3/L7_LB_S4_Product_Documentation_Report.md`](L7_LB_S4_Product_Documentation_Report.md) *(ce rapport)*

---

## 4. Documents modifiés

1. [`README.md`](../../README.md) : refonte en guide produit concis (objectif Pocoma, responsabilités CQRS, schéma 9 conteneurs / 5 JVM, prérequis principaux, commandes d'installation/démarrage/arrêt `./pocoma-local`, table des liens vers `docs/`, conservation du badge CI et des liens communautaires/licence).
2. [`docs/README.md`](../README.md) : ajout en tête de l'index produit structuré (Prise en main, Architecture produit, Exploitation et référence, Références techniques applicables, Archives) ; séparation claire de l'historique existant sous `## Archives et historique des jalons L1-L7 et V2` ; requalification du sous-titre `## Programme V3 courant` en `### Programme V3 (jalons L5–L7 — suivi historique)` sans supprimer ni déplacer aucune entrée historique.
3. [`docs/product/Functional_Model.md`](../product/Functional_Model.md) : ajout d'un bandeau `[!WARNING]` précisant son statut historique V2 (`/api/v1`) et pointant vers `overview.md` et `L7_Local_Playground_Public_API.md`.
4. [`docs/architecture/Architecture.md`](../architecture/Architecture.md) : ajout d'un bandeau `[!NOTE]` orientant vers `overview.md` et `runtimes.md`.
5. [`docs/guarantees/System_Guarantees.md`](../guarantees/System_Guarantees.md) : ajout d'un bandeau `[!WARNING]` précisant son statut historique V2 (`c3192f4`) et pointant vers `overview.md`, `persistence.md` et `L7_Local_Playground_Reservations.md`.
6. [`docs/operations/cmd-start-runtimes.md`](../operations/cmd-start-runtimes.md) : ajout d'un bandeau `[!NOTE]` indiquant qu'il décrit le câblage manuel dev-bench / Docker Compose et orientant vers `quickstart.md` et `local-instances.md`.
7. [`docs/testing/Functional_Local_Stack.md`](../testing/Functional_Local_Stack.md) : ajout d'un bandeau `[!NOTE]` clarifiant son rôle de fixture de test monolithique par opposition à `./pocoma-local`.
8. [`docs/v3/L7_Local_Playground_Quickstart.md`](L7_Local_Playground_Quickstart.md) : ajout du lien vers `quickstart.md` / `commands.md` et mise à jour factuelle de la mention de bail (`TTL SQL nominal réduit à 30 s` via `lease-duration-ms=30000`, distinction avec le budget d'observation `270 s` incluant scrutation et `claim-duration-ms=60000`).
9. [`docs/v3/L7_Local_Playground_Keycloak_Diagnostics.md`](L7_Local_Playground_Keycloak_Diagnostics.md) : ajout du lien vers `quickstart.md` / `troubleshooting.md` et mise à jour factuelle de la note de bail (`TTL 30 s` vs budget d'observation jusqu'à `270 s`).

---

## 5. Documents archivés ou indexés

Aucun fichier historique existant n'a été déplacé ni supprimé afin de préserver les consommateurs dans `app/` (tests d'architecture et fixtures PostgreSQL) et `scripts/`. Tous les ensembles historiques ont été indexés dans [`docs/archive/README.md`](../archive/README.md) :

- **Migrations SQL V3 archivées** : `docs/archive/v3-sql-migrations/` (`19` fichiers SQL : 11 WRITE + 8 READ, + `MANIFEST.json`).
- **Migrations SQL V2 isolées en test** : `app/infra-postgres-write/src/test/resources/db/migration/` (`31` fichiers) et `app/infra-postgres-read/src/test/resources/db/read-store/migration/` (`14` fichiers).
- **Documents historiques V2** : `docs/product/Functional_Model.md`, `docs/architecture/Architecture.md`, `docs/guarantees/System_Guarantees.md`, plans `docs/archive/*.md`, études `docs/architecture/`, dettes `docs/debts/`, plans/étapes `docs/plans/` et `docs/steps/`.
- **Rapports et preuves de qualification L1–L7** : conservés sous `docs/v3/`, `docs/v3/evidence/`, `docs/testing/evidence/`, `docs/operations/sql/`, et listés chronologiquement dans [`docs/README.md`](../README.md#archives-et-historique-des-jalons-l1-l7-et-v2).

---

## 6. Renommages effectués

- **Aucun renommage physique de fichier ou d'identifiant technique (`app/`, `scripts/`, `docker/`)** n'a été effectué en S4 :
  - L'inspection des consommateurs montre que `app/architecture-tests/`, `app/runtime-worker-materialize/`, `scripts/operations/setup-v3-database.py`, `scripts/local/prerequisites.py` et `scripts/local/runtime.py` référencent directement les chemins actuels, et que le calcul d'empreinte d'entrée (`PRE-INPUT-DRIFT`) surveille ces répertoires pour les instances existantes comme `demo`.
- **Requalification éditoriale uniquement** :
  - Dans [`docs/README.md`](../README.md), la section `## Programme V3 courant` a été renommée en `### Programme V3 (jalons L5–L7 — suivi historique)` et subordonnée à `## Archives et historique des jalons L1-L7 et V2`, afin que la documentation d'accueil présente Pocoma en tant que produit sans prérequis V3/L7.

---

## 7. Renommages différés

Les renommages techniques suivants restent volontairement différés (voir aussi [`docs/reference/configuration.md`](../reference/configuration.md#nomenclature-historique-identifiants-techniques-conserv%C3%A9s)) car ils impactent des contrats HTTP, des schémas PostgreSQL persistés, des scripts outillés ou l'empreinte d'instance existante :

| Identifiant actuel | Raison du report | Prérequis pour un futur renommage |
| :--- | :--- | :--- |
| Routes `/api/v3/...` | Contrat HTTP public exposé aux clients et aux collections Bruno (`J01`/`J02`/`J03`) | Décision contractuelle d'API + adaptation coordonnée des contrôleurs et tests Bruno |
| Schémas SQL `v3_write`, `v3_read` et tables `pocoma_v3_*` | Identifiants SQL persistés dans les baselines V1 actives et les métriques Grafana | Nouvelle baseline SQL + réinitialisation coordonnée des instances |
| Profils Spring `v3-http-admission`, `v3-http-read`, `v3-consumption-worker-dev`, `v3-read-worker` et propriétés associées | Couplés aux JARs `runtime-*`, aux `.properties` et aux suites de tests | Refactoring Java/ressources multi-slices + validation des suites |
| Répertoires/scripts `docker/v3-five-runtimes/`, `scripts/v3/`, `scripts/operations/setup-v3-database.py`, `scripts/bruno-v3/` | Référencés par `runtime.py`, `prerequisites.py`, `source_hash()` et tests | Adaptation simultanée de `scripts/local/*` et réinitialisation de `demo` (`PRE-INPUT-DRIFT`) |

---

## 8. Liens vérifiés

La vérification statique automatisée par script Python (résolution de chemin sur disque et correspondance des ancres Markdown `#...`) a contrôlé :

- **Documentation produit courante + index (`README.md`, `docs/README.md`, les 10 documents créés et `docs/archive/README.md`)** : **`307` liens internes vérifiés, `0` erreur**.
- **Lignes ajoutées dans les 7 documents existants annotés (`S4_ADDED_LINKS_IN_BANNER_DOCS`)** : **`14` liens vérifiés, `0` erreur**.
- **Corps historiques non modifiés des 7 documents annotés** : `76` autres liens historiques présents dans ces fichiers ; `31` liens V2 préexistants dans `docs/product/Functional_Model.md` (`7`) et `docs/guarantees/System_Guarantees.md` (`24`) pointent vers d'anciens modules/migrations V2 supprimés (conservés intacts pour ne pas réécrire ces documents historiques, désormais signalés par `[!WARNING]`).
- **Ce rapport (`docs/v3/L7_LB_S4_Product_Documentation_Report.md`)** : vérifié après son écriture finale sans erreur de lien.

---

## 9. Contrôles statiques exécutés

| Contrôle | Commande / Méthode | Résultat observé |
| :--- | :--- | :--- |
| Parité CLI `./pocoma-local` | `./pocoma-local --help` | Code `0` ; 9 sous-commandes `{start,upgrade-grafana,status,stop,reset,destroy,bruno,login,gui}` et 5 options (`--instance`, `--confirm`, `--dry-run`, `--interactive`, `--run`) conformes à `docs/reference/commands.md` |
| Liens & ancres Markdown | Script Python (résolution chemins + ancres GFM) | `307/307` liens docs courantes + `14/14` liens bandeaux = **0 erreur** |
| Chemins du dépôt cités | Script Python sur jetons `` `app/...` ``, `` `scripts/...` ``, `` `docker/...` ``, `` `docs/...` `` | `40` chemins uniques contrôlés ; les `3` seuls chemins absents sur disque sont les fichiers volontairement **interdits** dans `app/.mvn/` (`maven.config`, `jvm.config`, `extensions.xml`) documentés comme devant être absents |
| Baselines SQL actives | `find app/infra-postgres-{write,read}/src/... -name "*.sql"` | `1` baseline WRITE (`V1__initial_schema.sql`), `1` baseline READ (`V1__initial_schema.sql`), `31` test WRITE V2, `15` test READ (14 V2 + 1 primary test), `19` archives V3 |
| Périmètre inchangé code/runtime | `git diff --name-only -- app/ scripts/ docker/ .pocoma-local/` | Sortie vide (**0 modification**) |
| Hygiène diff & espaces | `git diff --check` | Code `0`, aucune erreur |

---

## 10. Réserves restantes et rapport de politique de vérification

- **Réserves** :
  1. Les identifiants historiques `V3`/`v3` des contrats HTTP, schémas SQL, profils Spring et répertoires outillés restent en place à l'issue de S4 (§7) pour préserver la compatibilité fonctionnelle et l'instance `demo`.
  2. `docs/product/Functional_Model.md` et `docs/guarantees/System_Guarantees.md` conservent dans leurs sections historiques `31` liens V2 cassés préexistants vers d'anciens chemins `infra-persistence-primary-jpa` / `engine-*` supprimés ; ils portent désormais un bandeau `[!WARNING]` explicite.
  3. Aucune vérification dynamique (`./pocoma-local start`, canari, Bruno, `./mvnw test`, clean-clone) n'a été lancée en S4 conformément au mandat purement statique ; les preuves dynamiques de référence restent celles établies au commit `16dada4430d594b12e8f2d9478d71835dc259eb9`.
  4. Le jalon `L7-LB` demeure ouvert en attente de `S5`.

```text
Start HEAD: 16dada4430d594b12e8f2d9478d71835dc259eb9
Expected production impact: NONE (scope strictement documentaire : README.md et docs/)
Observed production impact: NONE (git diff --name-only -- app/ scripts/ docker/ .pocoma-local/ vide)
Declared slices: NONE (vérification statique documentaire uniquement)
Verification commands executed:
- ./pocoma-local --help -> PASS (code 0 ; parité CLI confirmée)
- Script Python de vérification liens/ancres Markdown et chemins dépôt -> PASS (307 liens docs courantes + 14 liens bandeaux = 0 erreur ; 31 liens V2 préexistants signalés en archives V2)
- find app/infra-postgres-{write,read}/src/... docs/archive/v3-sql-migrations -name "*.sql" -> PASS (1 WRITE V1, 1 READ V1, 31 V2 test WRITE, 15 test READ, 19 V3 archivées)
- git diff --name-only -- app/ scripts/ docker/ .pocoma-local/ -> PASS (aucune modification)
- git diff --check -> PASS (code 0)
Unexpected slice crossings: NONE
Global architecture gate: NOT REQUIRED (aucun changement sous app/)
Full reactor: NOT REQUIRED (aucun changement sous app/ ; exclu par mandat)
Database verification: NOT REQUIRED (aucune modification SQL ; comptage des baselines/archives confirmé sur disque)
```

---

## 11. SHA final

- **SHA final** : voir le commit publié sur `feat/l7-lb-local-playground` portant la présente livraison S4.
