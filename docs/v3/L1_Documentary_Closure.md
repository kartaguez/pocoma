# L1 — Clôture documentaire et provenance de l’acceptation

Date : 2026-10-25. Dépôt `kartaguez/pocoma`, branche publiée `feat/v3-architecture`. Référence initiale locale du worktree de livraison et tête distante contrôlée : **`f50a413ee200a60b7747107abf96b62353e769e0`**.

**L1 ACCEPTED — internal V3 foundation.** Le responsable mandate cette clôture après livraison et audit indépendant L1-A…E. L’acceptation porte exclusivement sur les capacités internes réellement vérifiées. Aucun certificat V3 complet, aucune activation, aucun démarrage L2.

## 1. Sources et attribution

| Nature | Source / constat | Ce qui peut en être conclu |
|---|---|---|
| Résultats rapportés par Codex | [Rapport A/B](L1_AB_Execution_Report.md), [C](L1_C_Execution_Report.md), [D](L1_D_Execution_Report.md), [E](L1_E_Execution_Report.md), index/archives ci-dessous | Preuves internes exécutées et limites telles que publiées ; aucune preuve supplémentaire obtenue par rédaction |
| Contrôles automatiques de provenance L1 | Publication A/B/C/D/E, reçus des gates, comparaison des entrées avant/après et code testé→publication ; [Publication E](../testing/evidence/L1-DE/Publication.json) | A-02 PASS automatique documenté pour L1 ; identité des entrées testées et publiées dans le périmètre du contrôle, distincte de la correction métier |
| Audit indépendant et décision | GO A/B/C déjà enregistré dans le canon ; mandat explicite de clôture documentaire après livraison et audit indépendant de A…E, reçu le 2026-10-25 | Acceptation de la fondation interne transmise par le responsable. Aucun compte rendu détaillé distinct d’audit final L1 trouvé dans les fichiers suivis ; aucun constat détaillé, identité d’auditeur ou rerun ne lui est inventé |
| Contrôles de cette consolidation | Relecture des preuves et SHA, vérificateur L1 sur H→tête initiale, liens/ancres/chemins et diff documentaire | Contrôle documentaire/probatoire ; aucune nouvelle exécution Maven/PostgreSQL ni nouvel audit indépendant |

Les [audits conceptuels](V3_Independent_Conceptual_Audit_Report.md) et le [second audit architectural](V3_Second_Independent_Architectural_Audit_Report.md) sont antérieurs à L1 et ne constituent pas son audit final de code. Si le compte rendu détaillé final est rendu disponible, le rattacher avec sa référence et sa portée réelle, sans réécrire les verdicts historiques.

## 2. Lots et références immuables

| Lot | Portée acceptée dans L1 | SHA / preuve publiée |
|---|---|---|
| L1-A | Domaine pur et transitions | Code final `23f18a0126bf4ccd668aa1ad9f5d229d90666544` ; références initiales et corrections conservées dans le rapport A/B |
| L1-B | Persistance et historique PostgreSQL | Code final `887c24f3f266304e46c281cc61ba170f35d4de9a` ; livraison A/B `0942ecb3c1c07d6baf7bab3d136270e96bfda380`, tête A/B `56d658f9fd5313db2744fa11ff36699f4376aacb` |
| L1-C | Orchestration, CAS, transactions et reprises | Tête publiée `9dfe5a8d55d95bba29bb6a07e5c387c4ae8b0164` ; code final testé `2825f7b4c90e6b0c4b064e1166de6dd9a93f2bcf` |
| L1-D | Autorisations et calcul financier exact | `69acce7842364d5ee3881d41b8a70257fd09b81d` |
| L1-E | Intégration et vérification finale | Code testé `9899a2943760660893768fa4ba9452732ee17d50` ; dossier publié `05aa9f37c158fd7ee58f026072110fa640eafd0a` ; tête finale L1 `f50a413ee200a60b7747107abf96b62353e769e0` |

Tous ces objets Git existent et sont ancêtres de la tête finale L1 ; rôle du SHA testé distinct du SHA documentaire publié. Les références A/B déjà publiées sont conservées.

- [Plan L1 et clôture](L1_Design_and_Verification_Plan.md#27-clôture-documentaire-l1), [contrat de persistance B](L1_B_Persistence_Contract.md), [contrat transactionnel C](L1_C_Transaction_Contract.md).
- [Manifeste obligatoire L1 inchangé](L1_Proof_Manifest.json), [index A/B](../testing/evidence/L1-AB/Gate-index.json), [index C](../testing/evidence/L1-C/Gate-index.json), [index D/E](../testing/evidence/L1-DE/Gate-index.json).
- [Matrice SPEC-10 publiée](../testing/evidence/L1-DE/SPEC10-matrix.json), [reproduction/extraction D/E](../testing/evidence/L1-DE/README.md), archives et empreintes liées par les rapports. Les preuves restent hors du seul target effaçable.

## 3. Bilan et limites conservés

**91 méthodes obligatoires L1 PASS selon les preuves publiées**, uniques dans chaque gate final architecture/full : 14 A, 19 B, 34 C, cinq gardes A-01, 15 D et quatre E. Les répétitions des gates ne sont pas additionnées comme méthodes uniques. G-E final : 1289/0/0/1 et 83 obligatoires ; architecture et full reactor : chacun 1494/0/0/1 et 91 obligatoires sur le même H final.

**Full reactor et gate global d’architecture exécutés** à E selon le plan du jalon majeur. Maven BUILD SUCCESS ; **skip exploratoire V2 conservé**, `TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`, propriété `step3b.measure` absente. Couverture globale **INCONCLUSIVE** ; aucun skip obligatoire L1 et aucun skip requalifié PASS. L’échec initial G-E SSL PostgreSQL `08P01` et les exécutions remplacées restent à l’index ; la relance entière réussie est distinguée du diagnostic et ne gomme pas le FAIL historique.

- **A-01 : clôture technique pour L1**, gardes de pureté/ports et architecture C réexécutées.
- **A-02 : PASS automatique documenté pour L1**, trois gates E et leurs reçus/entrées, chaque commit documentaire H→publication et tête distante contrôlés. Le contrôle effectué ici sur H→`f50a413e…` retrouve les trois gates et le digest `3b109d557f6d772a8d146713cb2647dc1ff8a9fac36e8d58b47ea10ce2145c17`.
- **I-01 : complet pour L1, suivi transversal ouvert**, pour vrais writers Identity/Consumption/READ et retrait final de V2.

La matrice publiée contient 336 scénarios : 107 contributions internes exécutées et 229 reports hors L1, plus PRJ-04 retiré. Ce ne sont pas 107 scénarios globalement certifiés. Le manifeste et les rapports restent des snapshots de la campagne : leurs anciennes mentions d’audit en attente ne sont pas le statut courant, porté par le [plan directeur](V3_Implementation_Master_Plan.md).

**Aucun certificat V3 complet.** Autorité Identity réelle, admission/authentification HTTP, parcours READ/Consumption/Discovery complets hors périmètre ; acquisition et autorité initiales de banc ne sont pas des composants externes certifiés. Aucun PASS global de consentement Link, invitations réelles, sécurité API, Bruno/k6, maintenance READ ou performance. L’immutabilité des calculs internes ne certifie pas le stockage immuable des balances READ.

**Réserves de performance :** Grand Pot et historiques longs à qualifier : allocations du calcul exact (≈826 Mo cumulés par calcul dans une mesure D, sans mesure de mémoire résidente ni SLO), CPU/SQL, verrous, manifestes complets par V, stockage/index/WAL. Les comptages de requêtes et durées D sont indicatifs. Aucun seuil métier, cache durable ou calcul différentiel adopté pour les masquer. Propriétaires proposés L4/L6 puis confirmation L7/L8 dans le plan directeur.

## 4. Consolidation documentaire et vérification

Périmètre annoncé avant édition : documentation uniquement ; slices Maven **NONE** (primaire et secondaires), impact production attendu/observé **NONE**, franchissement inattendu **NONE**, gate global **NOT REQUIRED**, full reactor **NOT REQUIRED / non relancé**, base **NONE**. Aucune commande Maven, migration, PostgreSQL, Docker, Bruno ou k6 exécutée pour ce mandat.

Précontrôles : HEAD du checkout principal `90a3e9bc1a1fdd325d0c36e543824239cd85a431` sur `feat/runtime-monolith`, modifications préexistantes ; branche locale V3 portée par `/private/tmp/pocoma-v3-cleanup` à `ddca2827…`, en retard de 26 commits sur son suivi distant initial. Travail dans le worktree détaché L1-D/E déjà à `f50a413e…`, avec extractions de preuves non suivies préservées. Aucun reset/stash/déplacement ni ajout des fichiers préexistants au commit documentaire.

Commandes réellement exécutées (depuis la racine du worktree de livraison, sauf inventaire du checkout principal) :

```bash
git status --short --branch
git rev-parse HEAD
git branch -vv
git worktree list --porcelain
git remote -v
git ls-remote origin refs/heads/feat/v3-architecture
git rev-list --left-right --count HEAD...origin/feat/v3-architecture
python3 -B scripts/testing/verify-v3-l1.py --tested 9899a2943760660893768fa4ba9452732ee17d50 --published f50a413ee200a60b7747107abf96b62353e769e0 --proof-index docs/testing/evidence/L1-DE/Gate-index.json
git diff --check
python3 -B /tmp/pocoma-doc-check.py
git diff --name-status f50a413ee200a60b7747107abf96b62353e769e0
```

Recherches `rg`/`rg --files`, lectures intégrales canon/plan L1/SPEC-01…10 et rapports/manifeste L1, examen des plans historiques et de l’historique Git du canon, inventaire Python des POM/packages/Docker/Bruno/observabilité. Aucun contrôleur de liens documentaire dédié trouvé dans scripts/CI ; contrôle temporaire Python des chemins/ancres, objets/ascendance Git, statuts, preuves et périmètre des modifications. Le fichier temporaire de contrôle n’est pas un script de test du dépôt et n’est pas publié comme code.

Résultats : **PASS documentaire**, 27 documents Markdown et 504 références relatives vérifiés, aucun chemin/fragment cassé, SHAs et ascendance conformes, index final et 91 méthodes cohérents. Les worktrees préexistants, leurs index et modifications ont été comparés à l’état initial ; seules les éditions documentaires du worktree de livraison sont nouvelles. `git diff --check` passe. Aucun fichier exécutable ni mode de fichier modifié.

Le vérificateur L1 existant confirme les reçus et la provenance **jusqu’à la tête initiale L1** (sans option remote, tête distante vérifiée séparément). Sa liste stricte d’exclusions n’inclut pas le futur plan directeur, les README ou le plan L1 ; ne pas la modifier pour accepter cette consolidation. Après cette tête, contrôle séparé d’une liste fermée de Markdown, sans changement de mode ni fichier exécutable. Aucun nouveau verdict A-02 global n’est revendiqué sur le programme.

Les résultats des contrôles finaux et la référence du commit documentaire sont communiqués avec la publication, pour éviter un SHA autoréférent. Les [preuves du contrôle documentaire](../testing/evidence/L1-closure/Documentary-checks.json) conservent les vérifications avant commit ; le rapport de livraison précise la divergence distante finale.

## 5. Programme et arrêt

Documents créés : ce dossier et le [plan directeur L0–L8](V3_Implementation_Master_Plan.md). Documents actualisés : canon, plan L1, notes de statut des dix SPEC (sans changement des contrats/oracles), README principal et index documentaire. Rapports et manifeste de campagne L1 conservés intacts.

Les contradictions de statut sont résolues par portée temporelle : anciens plans/rapports décrivent leurs checkpoints ; la clôture courante accepte le périmètre interne. Aucun arbitrage métier nouveau. Le découpage L2–L8, la qualification du fournisseur, les formes physiques READ, les paramètres de protection/mesure, les noms/schémas finaux et les topologies restent à valider selon le plan directeur.

**STOP après publication documentaire sans force. Prochaine étape : revue du plan directeur, puis cadrage L2 après validation explicite. Aucun lancement L2, nettoyage V2/V3 ou construction Docker finale.**
