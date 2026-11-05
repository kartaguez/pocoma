# Pocoma V3 — L5/I2 — Décision humaine d’acceptation et de clôture

Date : **5 novembre 2026**. Dépôt : **`kartaguez/pocoma`**. Branche : **`feat/v3-architecture`**.

## 1. Décision et autorité

Source : mandat humain explicite « Mission Codex — Pocoma V3 — L5/I2 — Décision humaine de clôture — Publication documentaire uniquement », transmis dans la conversation le 5 novembre 2026. Le responsable accepte I2 dans le périmètre de sa qualification locale bornée et autorise cet acte, sa synchronisation documentaire, un commit et un push normaux sans force.

**L5/I2 — CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS.**

La décision fait suite à l’implémentation initiale, à son audit indépendant, au correctif ciblé des privilèges SQL, à l’audit indépendant de ce correctif concluant **PASS WITH EXPLICIT RESERVATIONS**, puis à l’acceptation humaine explicite. Les avis indépendants sont rapportés selon le mandat humain ; aucun rapport autonome d’audit I2 n’a été retrouvé dans les documents L5 du dépôt. Aucune commande, mesure ou campagne nouvelle n’est attribuée aux auditeurs. Le [rapport initial](L5_I2_Execution_Report.md) et le [rapport de correction](L5_I2_Post_Audit_Correction_Report.md) restent les sources des exécutions techniques.

Cet acte ne constitue ni certification globale de Pocoma V3 ou SPEC-10, ni homologation de production, ni clôture L5, ni dispense des obligations I3/I4/Q5.

## 2. Références Git et attribution des preuves

| Référence | SHA et portée |
|---|---|
| Implémentation initiale publiée | `df0c3a221ce20b4eb569616d7df621fa4090a32b` — livraison du snapshot initial qualifié |
| Correctif post-audit publié et HEAD initial de cette mission | `1fc07000e586394298b2f2cf7acbd5e84ff1c5e6` — V8, ACL et contrôles par colonne, tests ciblés |
| Référence distante vérifiée avant rédaction | `origin/feat/v3-architecture` au même SHA `1fc07000e586394298b2f2cf7acbd5e84ff1c5e6` ; divergence `0 0`, worktree propre |

Le HEAD initial correspond au SHA attendu : aucun delta documentaire intermédiaire à examiner. Le SHA final de publication figure dans le reçu de livraison de la conversation, sans auto-référence dans cet acte.

| Contribution existante | Résultat et attribution | Sources conservées |
|---|---|---|
| Qualification initiale WEB + COMMAND + Monolithe + Architecture, C-04 | **2 291/2 291 PASS, 0 FAIL, 0 ERROR, 0 SKIP** ; **170 oracles Architecture inclus**, sans les additionner ; snapshot livré à `df0c3a221ce20b4eb569616d7df621fa4090a32b` | [Rapport A–G](L5_I2_Execution_Report.md), [manifeste initial figé](L5_I2_Proof_Manifest.json), [archives initiales](../testing/evidence/L5-I2/README.md) |
| T2, B2 et reçus Pot initiaux | **85 identités T2 couvertes fraîchement dans C-04** ; **24 scénarios B2, 468 requêtes** ; **onze reçus Pot conservés** ; même attribution initiale `df0c3a22…` | [Matrice](L5_I2_Requirement_Evidence_Matrix.md), manifeste et archives initiaux ci-dessus |
| Qualification ciblée du correctif post-audit | **9/9 PASS sur PostgreSQL réel 17.11, 0 FAIL, 0 ERROR, 0 SKIP**, campagne **17,252554 s** ; snapshot corrigé publié à `1fc07000e586394298b2f2cf7acbd5e84ff1c5e6` | [Rapport de correction](L5_I2_Post_Audit_Correction_Report.md), [manifeste additif figé](L5_I2_Post_Audit_Proof_Manifest.json), [résultat](../testing/evidence/L5-I2-post-audit/result.json), [plan](../testing/evidence/L5-I2-post-audit/plan.json), [archive post-audit](../testing/evidence/L5-I2-post-audit/pocoma-l5-i2-audit-01.tar.xz) |

Les snapshots testés, leurs HEAD de départ et dirty states restent détaillés dans les manifestes ; les SHAs de livraison ci-dessus ne remplacent pas cette provenance. Le correctif porte sur la migration additive **V8**, la réduction des privilèges SQL d’admission, leur contrôle effectif par colonne et les tests ciblés de confidentialité/intégrité différée. Aucune gate Architecture ni full reactor n’a été exécuté pour ce correctif. Les parcours terminaux complets n’ont pas été rejoués après V8.

**Les 2 291 tests, les 170 oracles Architecture, T2 et B2 initiaux ne sont pas fraîchement exécutés au SHA corrigé.** Les preuves initiales sont héritées seulement dans leurs hypothèses non invalidées, selon la comparaison documentée du correctif. Les neuf tests récents qualifient le seul périmètre modifié. Cette clôture documentaire ne produit aucune preuve technique nouvelle et ne cumule pas ces contributions en une nouvelle gate.

## 3. Périmètre fonctionnel accepté

L’acceptation porte exclusivement sur :

1. Les onze commandes Pot publiques V3 : CreatePot, UpdatePotDetails, DeletePot, AddShareholders, UpdateShareholderDetails, UpdateShareholderWeights, DeleteShareholder, CreateExpense, UpdateExpenseDetails, UpdateExpenseFinancials, DeleteExpense.
2. Registration et Unlink publics.
3. L’admission asynchrone par `POST /api/v3/commands` et `POST /api/v3/identity/requests`.
4. L’idempotence S-03 durable et atomique.
5. La canonicalisation versionnée des intentions.
6. La concurrence PostgreSQL et le commit incertain dans le périmètre qualifié.
7. Les rôles SQL distincts et les privilèges resserrés après V8.
8. La séparation de l’admission et de l’autorité métier WRITE.
9. La résolution de SELF uniquement dans WRITE.
10. La compatibilité historique effectivement vérifiée, sous réserve d’évolution ci-dessous.
11. La réutilisation des résultats READ existants.
12. Les contrôles de ressources et les limites techniques initiales qualifiés localement.
13. Les scénarios B2 publics associés.

Link, Invitation et les composants futurs ne sont pas acceptés par cet acte. Le [runbook d’admission](L5_I2_Admission_Operations.md) conserve les conditions techniques et d’exploitation de la livraison.

## 4. Réserves explicites conservées

**R-I2-01 reste exclusivement l’identifiant historique du diagnostic C-02.** Pour éviter toute collision avec les numéros proposés dans le mandat de clôture, les quatre nouvelles réserves utilisent **R-CLO-I2-01 à R-CLO-I2-04**. AUD-I2-01/AUD-I2-02 désignent les constats du correctif, sans renuméroter la réserve initiale.

### R-I2-01 — Attribution du diagnostic C-02 (formulation initiale conservée)

**R-I2-01 — attribution du diagnostic C-02 :** les lectures originales du prédicat i5_w17 ne sont pas disponibles. Le défaut TOCTOU du helper est établi par contre-exemple et sa correction qualifiée ; le rejeu ciblé et la gate fraîche réussissent, mais ne reconstituent pas rétroactivement cet échantillon. Cette limite est conservée sans conclure à un défaut de production ni le nier.

### R-CLO-I2-01 / AUD-I2-01 — Compatibilité historique future

Le décodeur V1 construit des objets métier avant la recherche de l’association. Un durcissement futur des constructeurs, validations, formats ou règles de canonicalisation devra préserver la comparaison des intentions historiquement admises. Cette réserve est documentée ; aucune nouvelle abstraction de découplage n’est livrée par la correction ou cet acte.

Toute évolution concernée devra démontrer, sur une intention réellement admise avant le changement et en exerçant le constructeur modifié :

- Même ancienne clé et intention équivalente → même requestId, sans réinsertion.
- Intention différente comparable → **409**.
- Nouvelle clé → application des nouvelles règles.
- Aucun nouveau durable créé par erreur.

Les règles et octets canoniques historiques V1 ainsi que les anciennes clés restent nécessaires. Les tests de baisse de limite configurable et de restauration de clés ne prouvent pas la compatibilité face à un durcissement de constructeur. Le [rapport post-audit, AUD-I2-01](L5_I2_Post_Audit_Correction_Report.md#aud-i2-01--décision-dévolution-réserve-explicite) conserve les exigences détaillées de la future évolution.

### R-CLO-I2-02 — Preuve terminale non fraîche au SHA correctif

V8 modifie les fonctions SQL partagées pour réduire leurs projections de lecture. Les prédicats hérités sont préservés, leur conservation est contrôlée structurellement, et les tests PostgreSQL ciblés passent. **AUD-I2-02 est CORRECTED dans cette preuve locale ciblée.** Cependant, les parcours terminaux complets n’ont pas été rejoués après V8. La clôture accepte explicitement cette limite probatoire ; les preuves terminales initiales restent historiques et ne certifient pas une exécution fraîche au SHA corrigé.

### R-CLO-I2-03 — Issue physique indéterminée et disponibilité

Une terminaison PostgreSQL non observable entraîne une réponse technique et la rétention du permis d’admission. Avec **A=1**, un incident peut rendre l’admission indisponible jusqu’à résolution et intervention. Ce comportement respecte la sûreté transactionnelle retenue ; il ne garantit pas la disponibilité de production.

### R-CLO-I2-04 — Qualification bornée et environnement

Restent explicitement non homologués au-delà du périmètre local : mémoire et charge des grandes intentions ; capacité et débit de production ; pannes réseau réelles ; restaurations et rotations cryptographiques en conditions de production ; fournisseur AuthN externe réel (**EXTERNAL AUTHN NOT QUALIFIED**) ; paramètres de déploiement et d’exploitation ; complexité de la composition Spring ; coût des campagnes globales. Les mesures et plafonds initiaux du rapport ne deviennent ni SLO ni garanties générales.

### Réserves héritées L1–L4 et I1

Les réserves restent intégralement dans leurs périmètres respectifs : [L1](L1_Documentary_Closure.md), compléments [F](L1_F_Closure_Report.md)/[F2](L1_F2_Closure_Report.md), [L2](L2_Documentary_Closure.md), [L3](L3_Identity_Closure.md), [L4](L4_Read_Closure_Decision.md), [I1 R-I1-01…04](L5_I1_Acceptance_and_Closure_Decision.md). Cela inclut R-L2-01…05, G1/G2 INCONCLUSIVE, G3 NOT PASSED / NOT COMPLETED, R1–R4 Identity, lease180 non homologué production, qualification composite L4 sans full natif PASS, mémoire/disponibilité, Spring, AuthN et coût global. Aucun résultat historique FAIL/INCONCLUSIVE ni réserve n’est effacé ou réattribué.

## 5. Gouvernance et obligations restantes

| Jalon | Statut courant / suite |
|---|---|
| I1 | **CLOSED WITH EXPLICIT RESERVATIONS** ; libellé de son acte conservé : CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS |
| I2 | **CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS** |
| I3 | **NON CLOS — À CADRER**, prochain incrément Link/consentement réel selon le plan global ; aucun GO d’implémentation donné par cette publication |
| I4 | **NON CLOS — ULTÉRIEUR**, Invitation et sa qualification restent à réaliser sous leurs décisions ; aucun GO d’implémentation donné ici |
| Q5 | **DÛ — NON EXÉCUTÉ**, qualification finale et dossier d’acceptation L5 |
| L5 | **OPEN** |
| SPEC-10 globale | **NON ACQUISE** ; aucun certificat global ni GO production |

Pour **I3 et I4**, la décision humaine maintient l’absence de rejeu automatique de la gate Architecture globale et reporte cette preuve à **Q5**. Ce report n’est pas une exemption normative implicite. Les obligations différées sont tracées dans le [plan global L5 §12](L5_Supra_Entry_Audit_and_Implementation_Plan.md#12-incréments-dépendances-et-déclarations-de-vérification) : toute obligation Architecture déclenchée selon la [Reactor Verification Policy §17](../testing/Reactor_Verification_Policy.md) reste **REQUIRED — NOT RUN — DEFERRED TO Q5 BY EXPLICIT HUMAN INSTRUCTION** tant qu’elle n’est pas acquittée par une couverture effective. Les futures déclarations I3/I4 devront identifier leurs déclencheurs, hypothèses et preuves différées, sans les marquer PASS.

Le **full reactor natif reste obligatoire à Q5**, avec mutualisation G5/Architecture seulement sous couverture effective des méthodes, variantes, provenance et obligations (§§17/34/35 de la politique, [D6](L5_D6_Gates_Mutualization_Report.md)). B5, fournisseur réel, variantes et preuves externes applicables restent dus indépendamment. Les 170 oracles Architecture initiaux appartiennent à la qualification initiale I2 et n’acquittent pas automatiquement ces obligations futures. La Reactor Verification Policy reste inchangée.

## 6. Vérification et publication documentaires

Déclaration avant rédaction : **DOCUMENTATION ONLY — SLICES NONE** (primaire et secondaires NONE), DB NONE, impact productif interdit. Franchissement inattendu : **NONE**. Seuls six Markdown sont autorisés : cet acte, le rapport d’exécution I2, son plan opérationnel, le plan global L5, le Master Plan V3 et l’index documentaire.

| Contrôle technique de cette mission | Statut |
|---|---|
| Architecture globale | **NOT REQUIRED / NOT RUN** |
| WEB / COMMAND / Monolithe | **NOT REQUIRED / NOT RUN** |
| PostgreSQL | **NOT RUN** |
| Bruno | **NOT RUN** |
| Full reactor | **NOT REQUIRED / NOT RUN** — publication documentaire, Q5 reste dû |

Contrôles documentaires et Git : `git status --short`, `git branch --show-current`, `git rev-parse HEAD`, `git remote -v`, `git fetch origin feat/v3-architecture`, `git rev-parse origin/feat/v3-architecture`, `git rev-list --left-right --count HEAD...origin/feat/v3-architecture`, `git log -5 --oneline`, lectures `rg`/`cat`/`sed`, contrôle Python des liens/ancres locaux, SHAs, statuts, réserves, attribution des preuves et périmètre ; revue `git diff`, `git diff --check` et contrôle final indexé. Publication par ajout explicite des six Markdown, commit unique puis `git push origin feat/v3-architecture` sans force ; tête distante vérifiée par `git ls-remote origin refs/heads/feat/v3-architecture`, divergence et worktree revérifiés après publication. Les résultats effectifs et le SHA final sont fournis dans le reçu final.

Résultat des contrôles documentaires avant publication : **PASS**, six Markdown dans le périmètre, **472 liens locaux dont 24 avec ancres valides**, **28 SHAs complets résolus dans Git** ; corps historiques du rapport initial et du plan opérationnel identiques octet pour octet, R-I2-01 reprise verbatim. Statuts courants, identifiants uniques, attribution des deux livraisons et obligations I3/I4/Q5 concordants. `git diff --check` sans erreur ; contrôle final indexé avant commit.

Aucun Maven, JUnit, PostgreSQL, Bruno, Docker, test applicatif, expérimentation ou mesure n’est exécuté pour cette mission. Aucun code, SQL, ACL, migration, test, POM, script de qualification, SPEC ou politique n’est modifié. Archives C-04 et post-audit, manifestes, rapports producteurs et résultats historiques sont intégralement préservés. Aucun nouveau rapport technique redondant n’est créé.

**L5/I2 — CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS.**

**DOCUMENTATION ONLY — NO APPLICATION TESTS — NO ARCHITECTURE REPLAY — NO FULL REACTOR — NO SPEC-10 CERTIFICATION.**
