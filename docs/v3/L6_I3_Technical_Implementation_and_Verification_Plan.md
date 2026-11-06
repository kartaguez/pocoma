# Pocoma V3 — L6/I3 — Plan technique d'implémentation et de vérification

**Date :** 2026-11-06  
**Mandat :** `GO_L6_I3_SCOPING` ; plan uniquement, **pas de GO d'implémentation**.  
**Référence normative :** [rapport de cadrage I3](L6_I3_Scoping_and_Design_Report.md), [SPEC-09](SPEC-09_Performance.md), [SPEC-10](SPEC-10_Test_Certification.md), [politique de vérification](../testing/Reactor_Verification_Policy.md).

## 1. Contrat de livraison

Construire **un seul banc V3 paramétré** plutôt qu'un framework de benchmark : injection k6 à débit ouvert, appels de commandes V3 asynchrones, suivi des résultats et oracles READ indépendants, reporter JSON/CSV, collecte existante Prometheus/Grafana/PostgreSQL ; orchestrateur de campagnes et seeds synthétiques légers. Aucune modification des moteurs, migrations, sécurité HTTP ou métriques productives par défaut. Si une sonde manque, démontrer le manque, marquer `INCONCLUSIVE`, demander une évolution bornée séparée au lieu d'ajouter du code productif opportuniste.

**Fichiers prévus**, à vérifier contre les chemins du dépôt à l'ouverture de l'implémentation :
- `scripts/k6/v3-performance.js` : unique entrypoint, `SCENARIO`, charge `constant-arrival-rate`, `--summary-export`, budgets et limite VUs.
- `scripts/k6/lib/v3-api.js` et `v3-oracles.js` : admission durable, polling résultat, `minVersion`, C-03, Balance(P,V) via sonde indépendante autorisée, Identity binding, DeletePot ; contrôle timeout/censure.
- `scripts/performance/v3-preflight.py` : vérifications **non destructrices** allowlist, SHA, santé DB/API, séparation WRITE/READ, role/permissions, temps, monitoring, état READY.
- `scripts/performance/v3-seed.py` : seed déterministe dans environnements isolés uniquement, sans données personnelles ; option de restauration sûre, quantités exactes et digest.
- `scripts/performance/v3-run.py` : exécuteur séquentiel avec watchdog global et par phase, manifest d'essai, classification et arrêt sans retry.
- `scripts/performance/v3-collect.py` : séries, snapshots PostgreSQL, k6 JSON, état initial/final, checksums, erreurs, limites.
- `scripts/performance/README.md` : commandes réelles vérifiées, prérequis, procédure de restauration/cleanup, topologies et seuils.
- `docs/testing/evidence/L6-I3/` : preuves immuables, runs identifiés, manifest, résultats bruts, notices de censure ; éviter de committer des dump volumineux ou secrets.

Ces chemins sont **proposés**, les anciens `scripts/k6/{smoke,stress,projection_backpressure}.js` restent historiques et ne sont pas lancés pour V3. Réutiliser les composants Micrometer, Prometheus, Grafana, les collections Bruno V3, le script `scripts/operations/v3-rebuild-read.py` et les compositions existantes.

## 2. Ordre minimal des travaux, décisions et portes

| Étape | Production attendue | Contrôle et condition d'arrêt |
|---|---|---|
| T0 — ratification | D1–D6 du rapport approuvés, temps et seuils préenregistrés, configuration A/B disponible ou écart acté | **NO GO** en absence d'un budget expérimental et d'une cible non productive explicite |
| T1 — inventaire d'exécution | Matrice exacte des endpoints V3, outils, versions, métriques, tables/oracles et topologie, cartographie O01-A…N/F01-A…J | Manque critique : `INCONCLUSIVE`, ne pas inventer de preuve |
| T2 — squelette minimal | Préflight, driver k6 unique, appels asynchrones, ID corrélation en mémoire client et report d'erreurs | Unitaire/offline, syntaxe/lint, données synthétiques ; aucune charge |
| T3 — preuves d'oracle | Fixtures contrôlées : succès, nouvelle V, no-op, rejet, timeout, DeletePot, Balance(V), Identity réellement utilisable, 503 maintenance | Tous les faux positifs injectés doivent être rejetés ; pas d'assouplissement C-03 |
| T4 — observabilité | Relevés réels des séries et frontières par Consumption, histogrammes, Hikari/SQL/GC, surcharge sondes et cardinalité | Série absente = preuve non disponible, pas zéro mesuré |
| T5 — smoke borné | S0 puis reconnaissance ciblée, **max 70 min au total** avec sources et résultats archivés | Arrêt immédiat en cas de corruption, compteur incohérent, timebox dépassée |
| T6 — certification ratifiée | Enchaînement explicite S1–S8 et O01, puis S9/S10/S11 selon ordonnance approuvée | Un essai à la fois, budget par essai, contrôle de la stabilité avant suivant |
| T7 — décision | Rapport avec SLO par population, OPS, quotas, causalité plausible, seeds, bruts, réserves et toute zone NON PROUVÉE | Publication sans effacer FAIL, INCONCLUSIVE et TIMEBOX EXCEEDED |

**Verification slices I3 — construction de banc uniquement :** NONE pour la présente mission ; T1–T7 relèveront de l'autorisation suivante. En cas d'édition exclusive de scripts externes à `app/` sans changement métier, tests statiques de scripts et smoke V3 suffisent à l'inner loop ; aucune `architecture-tests` globale ni `./mvnw test` par défaut. Tout impact productif inattendu doit faire l'objet d'une réévaluation des slices WEB/COMMAND/EVENT/PROJECTION/BINDING et d'une nouvelle autorisation, conformément à la politique.

## 3. Prérequis de données / profils

- **Pot petit** 5 shareholders / 20 expenses, moyen 20/200, grand 100/2 000. Distributions déterministes : fractions, payer/participants, proportions de mutations, version globale et profondeur historique. Différencier créations, modifications non financières, Financials, poids, suppressions et multi-cibles.
- **Globaux normatifs** : 100 Pots/10k événements ; 10k Pots/1M événements ; 100k Pots/10M événements. Ne pas confondre événements et commandes, ni prétendre qu'un seed 10M est produit/chargé dans le budget d'un essai de 20 min. Etape de construction/restauration distincte, avec coût, quota stockage et arrêt à approuver.
- **Contention** : même Pot versus Pots indépendants, mêmes quantités commandées, mêmes nombres de participants et de workers, résultats de conflits différenciés.
- **F01-G** : snapshot exact de 10 000 commandes admises `PENDING`, sans finalisations ni projections requises terminées, workers arrêtés **sans interrompre l'admission** ; démarrage sous charge neuve 1/s, vérifier toutes les branches READ et slots jusqu'à convergence.
- **Rebuild** : référence WRITE immuable, préfixe vectoriel B (5 familles), 7 Consumptions READ et 28 checkpoints configurés, seeds contrôlés, READY vérifié ; conserver traces de maintenance et preuve d'absence de mutation WRITE.

## 4. Mesures et méthode

**Émetteur k6 :** distinguer itérations offertes/déclenchées/`dropped_iterations`, HTTP acceptées, admissions durables, demandes appliquées avec nouvelle V, rejet métier, erreur technique, terminalisation, disponibilité READ/Balance et timeout. p50/p95/p99 par population homogène et nombre d'observations ; aucun lissage des échecs. Départ du chronomètre monotone après confirmation de l'admission durable, arrêt lors du premier oracle C-03 valide, sondage 25 ms **proposé** avec limite d'inflight. Différencier timestamp observé et instant véritable de commit.

**Serveur :** temps admission, Discovery source→slot, attente éligible→claim, préparation, commit/finalisation, chacune des 7 branches READ, âge et taille backlog, retry/takeover/lease, coût READY(B). Traiter séparément les 5 flux WRITE. **Ne jamais sommer les p95 des phases parallèles**.

**Système :** CPU, RSS, heap, GC, threads, Hikari active/idle/pending, sessions/verrous PG, SQL time, lignes, plans EXPLAIN (ANALYZE seulement sur instance isolée), IO/WAL, disque, Prometheus scrape cost et cardinalité. Croiser latence/ressource avec N réellement lu ou exécuté, et attribuer par contrôle d'une seule variable.

**Contrôle de validité :** authentification/autorisation normales, `minVersion=V` et cohérence W≥V, Balance(P,V) exacte, DeletePot via état interne terminal, Identity via principal authentifié et nouveau bindingToken, arrêt READ 503 maintenu ; probes READ bornées et autorisées ; aucun secret, token, Pot/EUI id en labels Prometheus.

## 5. Calendrier borné (propositions à ratifier)

| ID | Script/paramètre envisagé | Budget total essai | Objet et oracle |
|---|---|---:|---|
| S0 | `SCENARIO=smoke` | 8 min | Validité du banc, pas certification |
| S1 | `SCENARIO=nominal`, 1/s, 20/200 A | 33 min | F01-A deux p95 ≤500 ms |
| S2 | `SCENARIO=identity`, Registration puis Link A | 55 min | F01-E p95 ≤500 ms chaque population |
| S3 | `SCENARIO=sustained`, 10/s A | 30 min | F01-B p95 ≤1 s, backlog stable |
| S4 | `SCENARIO=spike`, 50/s A | 28 min | F01-C p95 ≤3 s, retour nominal ≥10 min |
| S5 | `SCENARIO=contention` | 23 min | F01-H correction/reprises/drainage |
| S6-D | `SCENARIO=large`, 1/s 100/2000 A | 33 min | F01-D p95 ≤2 s |
| S6-I | `SCENARIO=history` par volume | 20 min/volume | F01-I sans SLO inventé |
| S7 | `SCENARIO=oidc` B | 23 min/parcours | F01-F/AuthN réelle |
| S8 | `SCENARIO=catchup` | 21 min | F01-G seuil 300 s ; observation de sécurité 360 s |
| S9 | `SCENARIO=rebuild` avec CLI existante | 25 min | Phases, READY(B), reprenabilité, sans SLO ajouté |
| S10 | `SCENARIO=idle` | 15 min | F01-J/O01-G |
| S11 | `SCENARIO=limits` | 20 min | Exploration, non homologation L8 |

Le détail de chaque budget (préparation, warm-up, mesure, collecte), l'enveloppe locale 70 min et le coût normatif théorique 246 min pour une passe maîtresse sont dans le rapport de cadrage. Trois répétitions comparables et ≥300 observations par population SLO restent des conditions SPEC-10 à satisfaire ou à faire déroger **par décision humaine** avant campagne. **Pas de prétendue certification rapide**. Aucun coût de création/chargement des grands seeds n'est inclus avant estimation approuvée. Arrêt watchdog ferme y compris sur collecte.

**Automate d'arrêt** : timeout de phase/essai/session ; cible inconnue ou productive ; défaut de métriques indispensable ; taux d'erreurs ou dropped iterations invalidant la mesure ; RAM/disque/WAL/pool dangereux ; worker durablement bloqué ; incompatibilité seed ; état de maintenance anormal ; fail-closed contourné. Après arrêt, conserver traces, émettre `TIMEBOX EXCEEDED` ou `INCONCLUSIVE`/`FAIL` selon cause, **zéro retry automatique**.

## 6. Procédure illustrative — NON EXÉCUTÉE

Exemples **prospectifs**, exécutables uniquement après création des fichiers et ratification des options ; noms et flags non garantis tant que T2 n'est pas achevé :

```bash
# Preflight non destructif ; mode explicitement local, cible allowlist
python3 scripts/performance/v3-preflight.py --target local-isolated

# Orchestration du smoke, bornage et provenance
python3 scripts/performance/v3-run.py --scenario smoke --budget-seconds 480

# Etat de rebuild READ sans destruction
python3 scripts/operations/v3-rebuild-read.py --status
```

Pour chaque campagne, `manifest.json` (SHA code, seed digest, CPU/RAM, versions, config, runtime, métriques, fenêtres, résultat), `k6-summary.json`, observations par commande pseudonymisées, métriques horodatées, snapshots SQL, diagrammes facultatifs, statut de validité et journal des arrêts. Une mesure défavorable et reproductible est un résultat valable, pas une demande de refactoring automatique.

## 7. Livrables finaux et clôture

Rapport `L6_I3_Implementation_and_Performance_Qualification_Report.md`, registre des exigences F01-A…J/O01-A…N, couverture REC-01/SPEC-09, index des runs, jeux de données, tableaux SLO et causalités plausibles, réserves avec affectations L7/L8. Signaler explicitement tout SLO `FAIL` ou `NOT EXECUTED`, sans dégrader normativement un FAIL en simple observation. Un PASS d'implémentation du banc ne vaut ni PASS PERF-01 ni clôture L6/G6.

## 8. Décisions humaines bloquant l'implémentation

Ratifier D1–D6 du rapport de cadrage, les ressources A/B réellement disponibles, la division des sessions, l'autorisation destructive sur **base synthétique isolée**, le budget de préparation des seeds 1M/10M, les coûts d'implantation des sondes READ indépendantes et les seuils de sécurité. Aucun GO de tests, seed ni implémentation n'est inclus dans le présent document.

**Verdict de plan :** `L6_I3_DESIGN_READY_FOR_HUMAN_APPROVAL`. Documentation seulement, aucune campagne lancée.
