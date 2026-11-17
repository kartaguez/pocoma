# Pocoma V3 — L6/I3 — Rapport de cadrage et conception métrologique

**Date :** 2026-11-06
**Référence :** `a24cd37255486481a4f11d4b81ab7895f470b223`
**Mandat :** `GO_L6_I3_SCOPING` — DOCUMENTATION ONLY
**État :** `L6_I3_DESIGN_READY_FOR_HUMAN_APPROVAL` sous arbitrages explicités ; **aucune campagne exécutée**.

## 1. Autorité et limites

Sources : [SPEC-09](SPEC-09_Performance.md), [SPEC-10](SPEC-10_Test_Certification.md) (OPS-01 O01-A…N, PERF-01 F01-A…J, C-03), [plan L6](L6_Design_and_Verification_Plan.md), [décision I1](L6_I1_Acceptance_and_Closure_Decision.md), [décision I2](L6_I2_Acceptance_and_Closure_Decision.md), [politique de vérification](../testing/Reactor_Verification_Policy.md). I1/I2 sont clos avec réserves et ne certifient pas I3. Aucun SLO n'est réputé acquis. État des outils fondé sur une inspection statique du dépôt au SHA ci-dessus, sans exécution.

**Différence capitale :** SPEC-09 fixe des **SLO** ; SPEC-10 §24.3 propose des durées de certification (20 minutes A/D/E, 15 minutes B, etc.), **trois répétitions comparables** et **au moins 300 mesures valides par population SLO**. Un essai court diagnostique la faisabilité mais **ne peut pas remplacer la preuve normative**. Toute réduction des fenêtres ou répétitions de certification nécessite un arbitrage humain explicite, avant GO, avec impact sur la portée de conformité.

## 2. Inventaire statique, réemploi et lacunes

| Objet | Présence constatée / chemin | Réutilisation admissible | Travail I3 |
|---|---|---|---|
| k6 HTTP | `scripts/k6/{smoke,stress,projection_backpressure}.js`, `lib/{workflows,pocoma-client,checks,scenario-data,prometheus-scraper}.js` | Modèles d'injection et utilitaires **seulement** | Nouveau banc V3 : ces scripts visent des endpoints synchrones historiquement supprimés ; **interdit d'en tirer un PASS V3** |
| Parcours API | Collections Bruno V3 et parcours de démonstration référencés par L6/I1 | AuthN, contrats de réponses, exemples de séquence | Relever les chemins exacts et factoriser les appels asynchrones ; Bruno n'est pas injecteur de débit |
| Génération | Fixtures PostgreSQL, initialisation V3 et démonstration déjà employées par L5/I1 | Seed déterministe et contrôles de cohérence | Audit de provenance, reproductibilité et isolation avant adaptation ; aucun générateur volumétrique V3 validé dans ce cadrage |
| Observabilité | Micrometer `pocoma_v3_*`, Hikari, Prometheus 5 s, dashboard Grafana 18 panneaux I1 + panneaux rebuild I2 | Réutiliser séries et exporters existants | Catalogue exact avec unités, frontières et couverture ; coûts et cardinalité à contrôler |
| SQL | PostgreSQL réel, états READ/WRITE, statistiques PostgreSQL disponibles selon configuration | `pg_stat_activity`, `pg_stat_statements` si réellement activé, EXPLAIN/BUFFERS dans banc isolé | Script de snapshots cohérents avant/après ; ne pas postuler l'extension activée |
| Maintenance READ | `scripts/operations/v3-rebuild-read.py`, `verify-l6-i2-rebuild.sh` | `--status`, `--dry-run`, `--resume` et orchestration de rebuild existante | Mesurer par phases et valider les gardes de sûreté ; le script Gate C n'est pas un benchmark |
| Compositions | `docker-compose.functional.yml`, `docker-compose.distributed.yml`, overlay observabilité référencé par Gate C sous `docker/compose/` | Local pour smoke et diagnostics ; distribution pour tests séparés si qualifiée | Config A/B de SPEC-09 **non prouvée** par une composition Docker locale |
| Archives | Résultats L1-F, L1-F2, L6/I1/I2 et décisions antérieures | Hypothèses et comparaison descriptive | Ne pas amalgamer benchmark de calcul pur, Gate fonctionnelle et SLO E2E V3 |
| Collecte de campagne | Pas de banc de certification V3 vérifié | Exports Prometheus/k6 réutilisables | Manifest JSON, résultats bruts, journal d'anomalies et digest des seeds |

Les fichiers du répertoire `scripts/k6/` sont déclarés **historiques/superseded** dans leur README ; `smoke.js` utilise des VUs constants et ses anciens seuils HTTP ne sont pas des SLO V3. Les noms de nouveaux scripts sont des **emplacements prévus**, non des fichiers existants.

## 3. Exigences non négociables

- SPEC-09 — Pots petit 5/20, moyen 20/200, grand 100/2 000 (participants/Expenses) ; historiques distincts 100/10k, 10k/1M, 100k/10M (Pots/événements) ; 1, 10, 50 admissions de commandes/s en boucle ouverte. Configuration **A** : PostgreSQL dédié 4 vCPU/8 Go et runtimes/API sur seconde machine 4 vCPU/8 Go ; injecteur/monitoring idéalement isolés. **B** : Keycloak réel isolé.
- F01-A : moyen, 1/s, p95 admission durable→`PotBalance(P,V)` exact ≤500 ms **et** admission→Pot complet C-03 ≤500 ms.
- F01-B : moyen, 10/s, p95 Pot complet ≤1 s, backlog non durablement croissant. F01-C : moyen, pointe 50/s, p95 ≤3 s et résorption. F01-D : grand, 1/s, p95 ≤2 s.
- F01-E : Registration et Link **séparés** : p95 admission durable→première lecture autorisée par bindingToken ≤500 ms, en A. F01-F : comparaison B sans inventer de nouveau SLO B.
- F01-G : exactement 10 000 commandes **admises en attente**, redémarrage workers avec 1 nouvelle/s : toutes les commandes initiales **et** projections requises complètes en ≤5 min. F01-H : contention même Pot, correction/convergence sans seuil de latence chiffré.
- F01-I : coût historique et SQL sur volumes réels. F01-J : coût de polling à vide, réveil et chaîne E2E.
- OPS-01 O01-A…N : couverture à établir par réemploi de preuves ciblées valides, tests de correction déterministes et expériences de charge ; ne pas réduire une obligation à une seule courbe de débit.
- Oracle C-03 : première **lecture authentifiée et autorisée** `minVersion=V` donnant W≥V et Pot complet avec Balance(W). `PotBalance(P,V)` doit être vérifiée séparément, même si W>V ; **DeletePot(D)** : oracle interne `CurrentPot→PotVersion(D).deleted=true`, jamais simple 404 ; Balance(D) absente par contrat. Visibilité suit sa branche indépendante.
- Les refus 404/503, erreurs, timeouts, `dropped_iterations` et observations censurées restent dans les dénominateurs et preuves. Aucun percentile des seuls succès n'est un substitut à la population totale.

## 4. Matrice minimale M1–M4 et correspondance normative

| Phase | Scénario | Normes | Jeu/charge | Critère | Statut de preuve |
|---|---|---|---|---|---|
| M1 | S0 préflight & oracle | O01-N, C-03 | Petit, 1 opération | Validité des jalons, des métriques et du fail-closed ; aucun SLO | Obligatoire, smoke |
| M1 | S1 moyen nominal & Balance exacte | F01-A, O01-H | Moyen indépendant, 1/s A | Deux p95 ≤500 ms | Obligatoire, certification |
| M1 | S2 Identity Registration/Link | F01-E | Séparément, A ; B dans S7 | Deux p95 ≤500 ms | Obligatoire, certification |
| M2 | S3 soutenu | F01-B, O01-H, O01-A/F | Moyen indépendant, 10/s A | p95 ≤1 s ; backlog stable | Obligatoire, certification |
| M2 | S4 pointe & retour | F01-C, O01-H | Moyen indépendant, 50/s A | p95 ≤3 s, drainage | Obligatoire, certification |
| M2 | S5 contention | F01-H, O01-B/C/E/K | Un Pot, concurrence pilotée | Pas de double effet ; progression et drainage | Obligatoire, qualification |
| M3 | S6 croissance | F01-D/I, O01-J, AUD-V3-013 | Grand 1/s, petits/moyens/grands ; historiques 10k/1M/10M | Grand p95 ≤2 s ; courbes SQL, CPU, mémoire et WAL | D obligatoire ; grands historiques sous réserve de faisabilité A |
| M1/M2 | S7 OIDC réel | F01-F | B, sélection ciblée | Sécurité et écart A/B ; sans SLO B inventé | Obligatoire, qualification |
| M4 | S8 backlog 10k | F01-G, O01-M | 10 000 commandes +1/s A | Tout terminé + projections ≤300 s | Obligatoire, certification |
| M4 | S9 rebuild READ | REC-01, C-02, RES-L6-I2-05 | Préfixe B contrôlé, puis WRITE concurrent en topologie adaptée | Oracle READY(B) exact, phase durations, reprise et convergence | Obligatoire pour caractérisation I3, non SLO inventé |
| M2 | S10 repos/retour | F01-J, O01-G/I/L | Repos puis source isolée/panne contrôlée | Polling et reprise mesurés, pas de boucle chaude | Obligatoire, qualification |
| M3 | S11 limites approfondies | O01-D, J/K, FIND-L1-01 | Partitions, workers, long historique | Corrélation métrique/SQL, sans homologation L8 | Exploratoire, si ressources disponibles |

**Couverture OPS-01 restante :** O01-D (takeover), I (erreur persistante), L (crash) doivent s'appuyer d'abord sur cas déterministes ciblés déjà qualifiés, puis une observation bornée si nécessaire. O01-E (softcap), O01-F (capacité), O01-J/K (sélectivité), O01-A/B (équité) exigent compteurs d'éligibilité et progression par Consumption. O01-N exige une validation contradictoire de frontières métriques, sans altération productive pendant la campagne. Une case non prouvée reste `NOT EXECUTED` ou `INCONCLUSIVE`.

## 5. Variables et réserves, expériences discriminantes

| Réserve | Variable explicative / sonde | Contre-épreuve |
|---|---|---|
| FIND-L4-03 Balance O(N) | N = nombre d'Expenses actives et de participations financières lues à V, pas nombre de Pots ; temps `MaterializePotBalance`, lignes/SQL, allocations/GC | Mutation d'étiquette vs Weight vs Financials, 20/200 → 100/2 000, et jusqu'à 200 000 participations ; isoler CPU fraction rationnelle et IO |
| FIND-L2-01 lease READ | Configuration historique 240 s, distincte des durées d'exécution observées ; renewals, takeover, âge slot, claim expiré | Traitement long/worker interrompu, mesurer progrès→reprise, vérifier fencing ; éviter un test de 240 s sans raison |
| FIND-L4-01 quota Q=2 | Limite volontaire de concurrence, refus, attente, pool, backlog | Mesurer offre/admissions/succès et débit terminal avec quota inchangé ; pas d'imputation à l'algorithme |
| FIND-L1-01 openComponent | Profondeur/version, requêtes SQL, lignes/plan, heap et durée | 100/1k/10k versions sous même mutation, puis profils 10k/1M/10M événements si accessibles |
| L6/I1 | Séries `pocoma_v3_*` réellement disponibles, histogrammes, étiquettes, coût de scrape, séries admission coexistantes | Inventaire compteur-by-compteur et pression sondage A/B ; aucun label Pot/EUI/token/trace |
| L6/I2 | Taille du préfixe B, checkpoints 28, 7 Consumptions READ, durée phase/READY, nombre de slots | Rebuild petit/intermédiaire, interruption/reprise, puis WRITE concurrent uniquement dans composition où WRITE reste physiquement disponible |
| OPS-01 | Poll à vide, source→slot, slot→claim, retries, soft cap, backlog d'âge | Repos→1 commande, déséquilibre entre familles, panne/takeover, partitions sélectives |

**Causalité :** chaque comparaison change une variable explicative (N, partitions, workers, quota, taille du préfixe) en gelant seed, SHA, topologie, JVM, distributions et sondes ; collecter plans SQL/row counts, métriques Hikari, CPU/GC/WAL pour départager SQL, CPU, attente scheduler, quota et surcharge de l'injecteur. La corrélation seule ne prouve pas une cause.

## 6. Protocole de mesure et preuves

Préflight sur cible allowlist non productive et identifiée ; base WRITE/READ séparées ; base dupliquée/snapshot pour essais destructifs, utilisateurs et tokens synthétiques, empreinte de seed, capacité disque disponible, metrics HTTP/Prometheus/SQL, rôle SQL non privilégié. Exécuteur refuse toute cible inconnue, URL prod, mélange de campagnes, worktree/source non identifiés, ou état `READ != READY` hors scénario maintenance explicitement autorisé.

Chronologie : restauration déterministe→contrôles fonctionnels→warm-up distinct→montée en charge→plateau mesuré→arrêt de l'injection→suivi des commandes déjà admises et du drainage→collecte→retour au repos→digest. Utiliser le temps **monotone côté client** après confirmation de l'admission durable ; ne jamais comparer des `nanoTime` multi-processus. Poll C-03 proposé initialement à 25 ms dans SPEC-10, avec plafond de sondes et mesure du biais ; premier succès = borne supérieure observée. Distinguer admission HTTP, résultat durable, Balance(P,V), premier READ complet, CurrentBinding réellement utilisable et READY(B).

Journal brut par commande (identifiant pseudonymisé, type, Pot synthétique, timestamps relatifs, statut, V, délais/timeout, censures), séries par famille, k6 JSON, `dropped_iterations`, Prometheus exposition, snapshots SQL, configurations/topologie, versions JDK/PG/k6/Docker, ressources, limite des horloges, anomalies et fichiers hashés. JSON/CSV de preuves sans token ni secret.

**Répetabilité :** une passe exploratoire courte peut arrêter l'investigation. Pour une conformité PERF-01, appliquer les **trois répétitions comparables** et **≥300 observations valides par population** de SPEC-10 §24.3, sauf dérogation humaine formelle. Conserver chaque FAIL séparément ; ne pas écraser un incident par la moyenne. p99 seulement interprétable si population et incertitude documentées.

**Classification :** `PASS` si oracle + échantillon + environnement + SLO requis respectés ; `FAIL` si invariant ou SLO rompu avec mesure valide ; `INCONCLUSIVE` si sonde, population, bruit, environnement insuffisants ; `NOT EXECUTED` si non lancé ; `TIMEBOX EXCEEDED` dès dépassement d'une fenêtre approuvée, sans relance automatique. Un échec SLO reste FAIL normatif, même si I3 peut se clore en tant que caractérisation avec réserve explicite et décision humaine.

## 7. Budgets : préflight/diagnostic et certification distincts

Les budgets ci-dessous **sont des propositions d'exploitation**, à approuver avant exécution ; les fenêtres de la colonne « norme » reprennent SPEC-10 §24.3 et ne sont pas raccourcies implicitement. **Budget de campagne = préparation bornée + warm-up + mesure + collecte et suivi**, avec watchdog indépendant, y compris pour l'échec.

| Cas | Prépa plafond | Warm-up | Mesure normative / exploratoire | Collecte & suivi | Budget max par essai |
|---|---:|---:|---:|---:|---:|
| S0 smoke V3 | 3 min | 1 min | 2 min exploration | 2 min | **8 min** |
| S1 F01-A | 5 min | 3 min | 20 min norme | 5 min | **33 min** |
| S2 F01-E | 5 min | 3 min | 20 min Registration **et** 20 min Link séparées | 7 min | **55 min** |
| S3 F01-B | 5 min | 5 min | 15 min norme | 5 min | **30 min** |
| S4 F01-C | 5 min | 5 min | 3 min pointe + au moins 10 min retour nominal | 5 min | **28 min** |
| S5 F01-H | 5 min | 3 min | ≥10 min norme contention | 5 min | **23 min** |
| S6 F01-D | 5 min | 3 min | 20 min norme grand | 5 min | **33 min** |
| S6-I historique | 8 min seed déjà restauré | 2 min | 5 min profil exploratoire / volume | 5 min | **20 min/volume** |
| S7 F01-F | 5 min | 3 min | ≥10 min / parcours ciblé (1 retenu par essai) | 5 min | **23 min/parcours** |
| S8 F01-G | 10 min seed préchargé | 0 | objectif ≤5 min + observation bornée à 6 min | 5 min | **21 min**, sans confondre seuil de 5 min et timeout |
| S9 rebuild | 8 min seed préchargé | 0 | plafond d'exploration 12 min, non SLO | 5 min | **25 min** |
| S10 idle/1er travail | 3 min | 0 | 8 min diagnostic | 4 min | **15 min** |
| S11 non-normatif | 5 min | 2 min | 8 min | 5 min | **20 min** |

**Le préchargement des 10 000 commandes, 1M/10M événements ou 100k Pots n'est PAS compris dans les 8–10 minutes de restauration** : il doit être réalisé ou importé en étape préparatoire distincte, soumise à son propre plafond et quota disque, à arbitrer après estimation sans banc réel. Impossible de garantir un budget global incluant 10M sans mesurer le coût de création/restauration.

**Deux enveloppes incompatibles à ne pas mélanger :**
1. *Reconnaissance locale*, uniquement S0 + diagnostics ciblés S6-I petit/S10/S9 petit : proposition **≤70 minutes** (sans gros seed), aucun verdict PERF-01.
2. *Certification A/B complète*, 1 passage S1,S2,S3,S4,S5,S6-D,S7,S8 : **246 minutes théoriques** si un seul parcours B et un seul essai de chaque, hors seed/historique/rebuild. Les trois répétitions normatives de campagnes comparables peuvent multiplier substantiellement cette durée (**borne conservatrice 3 × 246 = 738 minutes**), avant campagnes F01-I/J et REC-01. **Aucun GO global implicite** : décision explicite sur sous-campagnes, fenêtres et arrêt ; séquençage en sessions bornées, aucune exécution ouverte.

Arrêts automatiques par watchdog : temps > plafond ; environnement/DB ou horloges non fiables ; `dropped_iterations` ou erreurs invalidant population ; perte d'un jalon ou de la collecte indispensable ; workers bloqués durablement ; READ incohérent ; dérive seed ; croissance mémoire non maîtrisée ; espace disque/WAL/pools/locks PostgreSQL dangereux. Les seuils opérationnels de sécurité (RAM disponible, sessions, stockage, erreurs) sont à **étalonner et approuver avant GO** ; ne pas inventer une valeur normative. Arrêter sans supprimer les preuves, classer l'incident, **pas de rejeu automatique**.

## 8. Reconstruction et topologies

Pour rebuild READ : conserver `public.read_maintenance_state` et le verrou de session, FSM, 503 fail-closed, oracle `P_READY(B)` sur 5 familles WRITE, 7 READ et 28 checkpoints, slots/états relationnels. Mesurer phases drain/fencing/DDL/replay/oracle/READY, pause-reprise, backlog, ressource et empreintes WRITE intactes. Une reconstruction partielle n'autorise jamais READY. L'arrêt de `runtime-monolith` dans `docker-compose.functional.yml` **interrompt l'admission WRITE** : test de WRITE concurrent non interprétable ici. Utiliser la composition distribuée **uniquement après qualification opérationnelle ciblée**, sans déclarer homologation L8 ; sinon `NOT EXECUTED` pour WRITE continu.

Les essais locaux monolithiques valident la plomberie et la caractérisation locale, **pas les SLO de configuration A** si les deux machines 4 vCPU/8 Go dédiées ne sont pas réellement fournies. Configuration B nécessite Keycloak réel isolé ; la présence du Keycloak local dans Compose n'en fait pas une configuration B normative.

## 9. Arbitrages humains pré-GO

- **D1** : accepter le découpage « reconnaissance locale courte » puis « certification A/B normative longue » ; confirmer capacité machine A/B réelle ou qualifier explicitement `NOT EXECUTED` pour la certification indisponible.
- **D2** : approuver fenêtres et répétitions SPEC-10 §24.3 **sans dérogation**, ou prononcer une dérogation formelle dont le résultat ne pourra pas être déclaré PASS normatif inchangé.
- **D3** : figer la distribution des mutations, tailles de populations, cadence et budget des sondes (25 ms initial), plafond parallèle, identité des tokens, fenêtre de suivi et timeout de censure.
- **D4** : approuver budgets par essai et budget de session, seuils d'arrêt sûrs après préflight ; décider du coût admissible et de la méthode de création/restauration des seeds 1M/10M.
- **D5** : définir fenêtres et critères opératoires pré-enregistrés d'équité, vieillissement, régression de backlog et polling à vide (OPS-01), sans les déclarer SLO préexistants.
- **D6** : confirmer couverture de Link dans le scénario Identity E et topologie du WRITE concurrent pour rebuild ; ne pas changer les oracles de sécurité.

## 10. Verdict documentaire

`L6_I3_DESIGN_READY_FOR_HUMAN_APPROVAL` : périmètre, protocole, dépendances, risques, seuils existants, matrice et plafonds **proposés** sont explicités. **Pas de GO de construction ni de charge ; pas de mesures V3 nouvelles.** Les décisions D1–D6 conditionnent l'approbation du plan et l'ouverture de l'exécution. Toute incapacité réelle à fournir la configuration A ou la population requise donne `NOT EXECUTED`/`INCONCLUSIVE` sur la norme concernée, jamais PASS de convenance.
