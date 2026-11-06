# Pocoma V3 — L6 — Plan consolidé de conception, observabilité, exploitation et vérification

**Date :** 6 novembre 2026
**Branche :** `feat/v3-architecture`
**SHA de cadrage initial :** `0d11082e397337b4546dd751defec146ee5095e0`
**Autorisation :** `GO_L6_SCOPING_ONLY` puis `GO_L6_I1_IMPLEMENTATION_WITH_TARGETED_CORRECTIONS`
**État :** `SCOPING ACCEPTED — I1 CLOSED (LOCAL OPERATIONAL V3 PASS WITH EXPLICIT RESERVATIONS) — I2 DESIGN FINAL CONSOLIDATED (READY FOR IMPLEMENTATION GO) — I3/G6 PENDING`

## 1. Autorité, statut et interdictions

Références normatives : [Master Plan](V3_Implementation_Master_Plan.md) §§3–5, [Canon](V3_Canon_and_Roadmap.md), [SPEC-08](SPEC-08_Observability.md), [SPEC-09](SPEC-09_Performance.md), [SPEC-10](SPEC-10_Test_Certification.md) §§10–12, [politique de vérification](../testing/Reactor_Verification_Policy.md), [décision L5/Q5-D](L5_Q5D_Formal_Closure_Decision.md), [audit Q5-C](L5_Q5C_Quality_Performance_and_Restructuring_Audit.md) et [plan L5](L5_Supra_Entry_Audit_and_Implementation_Plan.md).

**Précondition vérifiée :** acte Q5-D accessible sur `feat/v3-architecture`. L5 est `CLOSED WITH EXPLICIT RESERVATIONS`; Q5-B6 rapporte 61/61 modules, 2 344/2 344 tests, Architecture 170/170, Bruno 70/70. Ces chiffres sont des preuves historiques L5, **pas** un PASS L6.

**Périmètre historique L6 :** « Performance & Exploitation », comprenant (1) instrumentation et qualité des mesures **O-01**, (2) reconstruction totale et exclusive READ **REC-01 / C-02**, (3) équité, reprise et coût opérationnel **OPS-01**, (4) qualification physique **PERF-01 F01-A…J**. L'orientation « observabilité d'abord » précise leur ordre, sans réduire ces obligations. Pour SPEC-10, les scénarios RCV01-A…N et O01-A…N sont conservés dans leur intégralité ; toute adaptation doit préserver chaque oracle. Aucun SLO n'est réputé acquis.

**Hors périmètre :** optimisation du quota Q=2, réduction du lease READ 240 s, changement du calcul Balance O(N), remplacement SQL openComponent, isolation architecturale des workers/résultats, redécoupage Maven, retrait de V2/DDL, changement des invariants transactionnels, distribution et packaging final. Aucune proposition CH-01…CH-09 de Q5-C n'est un GO. L7 assume la convergence V2→V3 et la requalification des mesures invalidées par changement structurel ; L8 assume provisionnement final, images, dashboards et installation vierge.

## 2. Architecture métrologique minimale et contrats

Réutiliser et vérifier **avant de compléter** l'existant : `contracts-observability`, `TraceContext`/`X-Trace-Id`, Actuator, Micrometer, exposition Prometheus, collecte PostgreSQL, Hikari et scénarios k6 historiques (ces derniers ne valent pas preuve V3). Aucun framework/backend de tracing nouveau sans preuve de besoin. Positionner les adaptateurs de mesure en bordure des engines, orchestrators, ports d'infrastructure, HTTP et compositions Spring ; domaine pur et sémantique métier inchangés. Des métriques opérationnelles ne sont pas une autorité de vérité transactionnelle.

**Frontières O-01 :** T0 réponse HTTP d'admission durable ; T1 source committée et découvrable ; T2 slot enrôlé ; T3 claim durable ; T4 fin de préparation ; T5 effets committés et slot supprimé ; T6 consultation READ réellement disponible. Mesurer admission→résultat et admission→READ complet indépendamment ; Discovery, attente Engine, préparation, finalisation, chacune des branches de projection, retries, refus, erreurs et quarantaine séparément. Ne jamais additionner les p95 des branches parallèles. Méthode explicite par mesure : monotone local, sondage client bornant la visibilité, proxy durable ou observation SQL ; ne pas présenter des timestamps pré-commit comme instant de commit, ni comparer des nanoTime de processus distincts.

**O-01 par famille :** `MaterializePotVersion`, `MaterializePotBalance`, `MaterializePotVisibility`, `MaterializeInvitation`, `MaterializeCommandResult`, `IdentityRequestResult` et `CurrentBinding` : mesurer chaque chemin utile et sa disponibilité ; les sept Consumptions READ du contrat de reconstruction restent distinguables. Le jalon C-03 « Pot complet » est une lecture authentifiée/autorisé à `minVersion=V` retournant W≥V avec composants et balance de W ; `PotBalance(P,V)` technique reste un oracle distinct. Pour DeletePot, oracle interne `PotVersion(P,D).deleted=true` et non un simple 404. Pour Identity, binding réellement utilisable par son propre principal authentifié.

**Séries envisagées** (noms à confronter aux conventions Micrometer existantes) : `pocoma_admission_duration`, `pocoma_consumption_phase_duration`, `pocoma_consumption_attempts_total`, `pocoma_consumption_backlog`, `pocoma_consumption_oldest_eligible_age`, `pocoma_projection_availability_duration`, `pocoma_read_duration`, `pocoma_read_quota_rejections_total`, `pocoma_lease_renewal_duration`, `pocoma_lease_takeover_total`, `pocoma_jdbc_pool_active`, `pocoma_rebuild_state`. Adapter les noms avant implémentation sans créer des doublons. Latences p50/p95/p99 via histogrammes au grain pertinent, non moyennes ; compteurs de succès/rejet/erreur, débit offert/admis/appliqué/terminal, backlog par famille et âge, CPU/RAM/GC, pool JDBC, PostgreSQL SQL timings/EXPLAIN (avec précautions pour ANALYZE), verrous, WAL et sessions.

**Cardinalité/sécurité :** seuls labels bornés (family, operation, phase, outcome, runtime et partition si justifiée) ; interdits dans labels : Pot/EUI/User/Request/Event/Consumption IDs, trace IDs et tokens ; aucune valeur de token en log. Les identifiants de corrélation sont réservés aux journaux de diagnostic contrôlés. Échantillonnage, rétention et surcharge des sondes à caractériser ; collecte de backlog via requêtes bornées, pas un `COUNT(*)` coûteux par requête HTTP.

## 3. Incréments groupés et matrice exigences → livraison

| Incrément | Objectif / modules pressentis (à confirmer par inventaire de code) | Dépendances | Livrables / critère d'acceptation | Effort indicatif |
|---|---|---|---|---|
| **I1 — Instrumentation & preuve des métriques** | `contracts-observability`, HTTP/WEB, compos Spring, Consumption Discovery/Engine, infra JDBC/READ ; adapters de monitoring | L5 clos ; configuration métrique existante auditée | **CLOSED (LOCAL OPERATIONAL V3 PASS WITH EXPLICIT RESERVATIONS)** — Catalogue O-01, timers/compteurs/histogrammes, stack locale Prometheus/Grafana provisionnée, Bruno 8/8 et progression démontrée, cf. [Décision I1](L6_I1_Acceptance_and_Closure_Decision.md) | 2 j dev + 0.5 j qualif |
| **I2 — Exploitation & reconstruction READ** | compositions workers READ, persistence READ, lifecycle, commandes opératoires sans DDL imposé | I1 pour observer ; contrat REC-01 | **CONCEPTION FINALE CONSOLIDÉE (READY FOR GO)** — Autorité indestructible `public.read_maintenance_state` ; barrière fail-closed 503 ; exclusion workers à 5 niveaux (inhibition, scale=0, pg_stat_activity, REVOKE USAGE, DROP AccessExclusiveLock) ; FSM 4 états / 9 phases avec `--resume` déterministe ; oracle $\mathcal{P}_{\text{READY}}(B)$ sur 28 checkpoints et 0 slots $\le B$ ; watchdog 180s ; 3 gates ciblées ($\le 8$ min, `-DfailIfNoSpecifiedTests=false`, aucun full reactor). [Plan technique I2 consolidé](L6_I2_Technical_Implementation_Plan.md), [Rapport de cadrage consolidé](L6_I2_Scoping_and_Design_Report.md) | 4–7 j dev |
| **I3 — Bancs & qualification de performance** | k6/Bruno, runtime-web-api/monolith, workers, PostgreSQL, observabilité | I1, I2 ; configuration A/B et protocole ratifiés | Seeds reproductibles, campagnes SPEC-09 et PERF-01 F01-A…J, RAW data, SLI et SLO comparés ; OPS-01 O01-A…N consolidés ; constat de charge et réserves | 4–7 j dev + durée physique des campagnes |
| **G6 — Qualification et clôture** | preuves, rapports et gates selon politique | I1–I3 et arbitrages | Conformité O-01/REC-01/OPS-01/PERF-01 documentée, audit contradictoire, gates majeures requises, décision humaine distincte | 1–2 j hors campagnes |

**G6 n'est pas un quatrième chantier fonctionnel.** I1 et une partie du banc k6 peuvent être préparés en une seule passe ; l'exécution du banc exige une instrumentation vérifiée. I2 requiert la barrière et READY ratifiés avant implémentation. Pas de micro-incrément pour une métrique ou un composant isolé. Les estimations sont des charges de planification, non des budgets de tests ni une garantie de durée.

**Matrice normative compacte :** SPEC-08/O-01 → I1, I3, G6 ; SPEC-10/REC-01 et C-02 → I2, G6 (RCV01-A…N) ; SPEC-10/OPS-01 → I1, I2, I3, G6 (O01-A…N) ; SPEC-09/PERF-01 → I1, I3, G6 (F01-A…J et profils complets) ; C-03 Pot/Balance/Delete/Identity → I1, I3 ; sécurité et invariants SPEC-01…07 → transversal à I1–I3, sans redéfinir les métiers. Toute exigence non démontrée reste explicitement `NOT PROVEN`, jamais soldée par une mesure de substitution.

## 4. Protocole de mesures et décisions post-L6

| Question différée | Indicateur, point et méthode | Charge / reproductibilité / limites | Décision future éclairée |
|---|---|---|---|
| Quota READ Q=2 | refus 503, utilisation des deux permis, temps READ, Hikari, débit terminal ; HTTP client + timers serveur | 1/10/50 commandes/s et consultations concurrentes, quotas inchangés ; saturation artificielle à distinguer de SQL | configurer/élargir ou conserver Q |
| Lease 240 s / reprise | renouvellements, échéances, takeover, délai dernier progrès→reprise, slots orphelins ; logs/PG et crash déterministe | 7 READ, 4 partitions, panne sous charge et à vide ; dépend du mode de panne et du timing | ajuster lease ou regrouper workers |
| Balance O(N) | CPU, durée projection, lignes SQL, allocations/GC, nombre requêtes ; timers, pg_stat_statements/EXPLAIN | petit/moyen/grand, évolution du nombre d'Expenses et profondeur historique ; ne pas attribuer toute latence E2E à Balance | conserver calcul pur ou étudier incrémental |
| SQL openComponent | plan, buffers, locks, lignes et temps p95 par mutation ; PostgreSQL | tables réellement peuplées 10k/1M/10M événements ; ANALYZE consomme des ressources | indexes/réécriture justifiés |
| Identity isolée ? | temps attente/terminalisation Identity sous charge projections, pools et threads ; sondes séparées | configuration B Keycloak réel, jetons réutilisés hors scénario login ; distinguer AuthN/Identity | dédier ressources ou conserver partage |
| Résultats rapides | admission→CommandResult/IdentityResult vs admission→Pot complet ; backlog résultats vs projections | charge mixte et backpressure ; branches parallèles, quantiles non additifs | découplage des résultats |
| Partage ressources | occupation Hikari, pool wait, CPU, équité, p95/p99 de chaque famille | même volume, compositions comparables, répartition et capacités figées | séparer certains participants |
| Runtimes et compositions | débit utile, E2E, CPU/mémoire/pools par instance ; même seed et charge | configuration A, paramètres identiques et environnement stable ; aucune causalité sans expérience contrôlée | choix de composition post-L6 |
| Coût des reprises | âge backlog, retry, poll SQL, WAL, temps reprise en cas de crash | backlog 10 000 et injection de panne, préfixes et slots observés ; bornes de sondage | politiques de disponibilité/reprise |

Pour chaque campagne archiver commit technique, seed, scripts, versions DB/JDK, topologie, pool/lease/quota, partitionnement, chronologie, mesures brutes, erreurs, résultats non censurés et métadonnées machines. Aucune extrapolation à production distribuée non qualifiée.

## 5. Profils, critères quantitatifs et acceptation

**Configuration A normative :** PostgreSQL dédié 4 vCPU/8 Go ; API + runtimes dédiés 4 vCPU/8 Go ; générateur et monitoring idéalement isolés. **Configuration B :** même base et Keycloak réel isolé, campagnes Identity/AuthN ciblées. Jeux : petit 5/20, moyen 20/200, grand 100/2 000 (participants/dépenses), historiques 100 Pots/10k événements, 10k/1M, 100k/10M ; nominal 1 commande/s, soutenu 10/s, pointe 50/s. Il faut distinguer Pots indépendants et contention sur même Pot.

**SLO SPEC-09 à prouver, et non à réinterpréter :** moyen à 1/s p95 ≤ 500 ms admission→READ complet et p95 ≤ 500 ms admission→Balance(P,V), distincts ; Identity nominal p95 ≤ 500 ms admission→binding utilisable ; moyen 10/s p95 ≤ 1 s, moyen 50/s p95 ≤ 3 s ; grand à 1/s p95 ≤ 2 s ; rattrapage de 10 000 commandes initialement en attente, avec 1 nouvelle commande/s, ≤ 5 min jusqu'à résultat et toutes projections pertinentes. Stabilité à 10/s : backlog non croissant durablement. Contention même Pot : correction, progression et backlog résorbé, **sans** SLO de temps inventé.

**Oracles :** première consultation API réellement autorisée à `minVersion=V`, W≥V et balance cohérente de W ; matérialisation technique de chaque Balance(P,V) ; DeletePot prouvé via état supprimé interne, pas simple 404 ; toutes les familles READ complètes et exactes lors du rattrapage. Mesurer erreurs/rejets/timeouts et itérations k6 abandonnées ; ne pas biaiser les quantiles en omettant les échecs.

**Arbitrages pré-campagne :** durée des plateaux et répétitions, taille d'échantillons p99, intervalle de polling et biais maximal, distribution exacte des commandes, volumes générés et stratégie de seed, seuil opératoire « pas de famine »/coût polling pour OPS-01, calibration de la pression d'observabilité et seuil de rattrapage. Les SLO validés ci-dessus ne sont pas renégociés silencieusement.

## 6. REC-01 et OPS-01 — sécurité opérationnelle

La reconstruction est une maintenance exceptionnelle avec **indisponibilité totale READ temporaire**. Prouver arrêt physique de tous writers/transactions READ antérieurs au reset (pas simple expiration de lease), réinitialisation atomiquement cohérente des projections, slots, claims/partitions et checkpoints READ, maintien intégral de WRITE, relance sur les **cinq séquences immuables WRITE**. Prélever cinq préfixes sûrs B, attendre le progrès des **sept** Consumptions READ et la complétion de tous leurs slots enrôlés ≤ B ; les publications post-B ne retardent pas artificiellement READY. L'exclusivité perdue ou un rebuild interrompu interdit READY. Un replay indépendant possède un nouvel ID et une destination isolée/idempotente explicitement prouvée.

Exécuter comme oracles discriminants RCV01-A…N, y compris writer tardif, panne entre reset/reprise, quarantaine préalable, rejets de restore partielle, observabilité de READ indisponible, autorisation après READY, présence de WRITE continu et valeurs des sous-versions historiques. Exécuter O01-A…N, équité des deux boucles, anciens slots non affamés, retryAfter, claims expirés, panne/takeover, coûts du polling à vide, sélection sur historiques volumineux et découverte sous backlog. Une métrique qui existe mais ne couvre pas la bonne transition est un FAIL de preuve.

## 7. Stratégie de vérification strictement bornée

**Les commandes ci-dessous sont prospectives, non exécutées.** Exécuter depuis `app/`, en choisissant les vrais tests et modules atteints après inventaire de code. Les sélecteurs `-Dtest` sont des placeholders à remplacer par des noms vérifiés ; ne jamais lancer littéralement `<...>`. Les budgets sont **plafonds de pilotage proposés**, à ratifier avant GO ; dépassement = arrêt et rapport d'écart, pas retry automatique. Maven `timeout` externe si disponible et sortie/artefacts conservés. Pas de full reactor ni Arch globale par incrément.

| Gate | Périmètre et commande-type | Budget plafond proposé | PASS / arrêt / rejeu |
|---|---|---|---|
| **I1-U** | Modules d'observabilité/engines impactés : `./mvnw -pl contracts-observability,engine-consumption -am -Dtest=<tests-vérifiés> -Dsurefire.failIfNoSpecifiedTests=false test` | 4 min | Mapping de phase, issues et cardinalité corrects ; FAIL/timeout stop, 1 diagnostic sans relance |
| **I1-INT** | WEB et consommation réellement touchés, `./mvnw -pl runtime-web-api,runtime-event-consumption-worker -am -Dtest=<tests-vérifiés> -Dsurefire.failIfNoSpecifiedTests=false test` | 8 min | HTTP + événements mesurés ; si graphe excessif, découper explicitement avant exécution |
| **I2-PG** | PostgreSQL de maintenance READ et workers concernés, `./mvnw -pl runtime-task-consumption-worker -am -Dtest=<REC-tests-vérifiés> -Dsurefire.failIfNoSpecifiedTests=false test` | 12 min | Barrière, rollback, READY et absence de corruption ; stop au premier défaut non localisé |
| **I2-OPS** | JUnit/PG ciblé O01/RCV01, `./mvnw -pl runtime-event-consumption-worker,runtime-task-consumption-worker -am -Dtest=<OPS-tests-vérifiés> -Dsurefire.failIfNoSpecifiedTests=false test` | 12 min | Progression/équité/reprise prouvées, zéro doublon |
| **I3-SMOKE** | Bruno/k6 V3 ciblé + collecte O-01 à charge légère ; commande figée après inventaire | 10 min | Cohérence des sondes/observations, sans qualifier PERF-01 |
| **I3-PERF** | k6 nominal/soutenu/pointe/rattrapage, config A/B et profils SPEC-09 ; commandes et fenêtres figées après ratification | **Non bornable à 10 min** : budgets par campagne à approuver avant exécution | Seuils normatifs + correction, raws et sessions PostgreSQL ; aucun retry compensatoire |
| **G6-ARCH** | `./mvnw -pl architecture-tests -am test` uniquement si architecture/frontière touchée ou gate majeure requise | indicatif 25 min, à calibrer | Global PASS requis au jalon majeur, sans réexécution de convenance |
| **G6-FULL** | `./mvnw test` uniquement au jalon de clôture majeure imposé par politique, mutualisé avec architecture et composants concernés | indicatif **130 min**, au regard du 83 min Q5-B6 | Full natif non filtré, zéro FAIL/ERROR/SKIP non justifié ; un seul essai, incident documenté |

**Gates et politique :** le full et l'Arch globale sont différés à G6, sauf franchissement architectural explicitement constaté imposant une nouvelle déclaration de périmètre. Favoriser une vérification JUnit ciblée puis un gate de slice représentatif ; pas de doublon systématique JUnit/Bruno. Aucun replay automatique, `-rf` de rattrapage ou loop correction/test illimitée. Une correction ne donne droit qu'à une vérification ciblée justifiée et nouvelle déclaration d'impact ; un échec peut rester en réserve de clôture au lieu de consommer des heures.

**Provenance :** une fiche par invocation (SHA, commande exacte, sélection, environnement, durée et timeout, exit code, XML frais, résultats FAIL/ERROR/SKIP, logs utiles, fichiers de sortie) ; stocker les données brutes, ne pas réécrire les preuves historiques L5. La campagne PERF-01 n'est acceptée que si instrumentation O-01 et oracles métier sont fiables.

## 8. Registre de risques, dépendances et arbitrages humains

| Référence | Risque / dépendance | Réponse L6 | Propriétaire de décision |
|---|---|---|---|
| FIND-L4-01 | quota READ Q=2, refus potentiels | mesurer série de refus et saturation ; **ne pas changer Q** | post-L6 |
| FIND-L2-01 | lease READ **240 s** (état L5), reprise lente | mesures renouvellement/crash/claim ; aucun raccourcissement | post-L6 |
| FIND-L4-03 | Balance O(N), grand Pot | profiler CPU/SQL/latence ; préserver calcul exact pur | post-L6 |
| FIND-L1-01 | `openComponent` sous historique long | EXPLAIN/BUFFERS et verrouillage représentatifs | post-L6 |
| AUD-Q5C-07 | résultats rapides couplés aux projections | mesurer p95 et occupation partagée ; aucun découplage | post-L6 / L7 |
| RES-ENV-01 | Docker local non représentatif | config A/B normatives, rapports limites réseau/CPU | L6 / L8 |
| FIND-TRANS-01, FIND-L2-02, AUD-Q5C-08 | composition modulaire, branches autorité, legacy | inventorier coût sans suppression ni migration | L7 |
| REC-01 | exclusivité réelle maintenance et READY sécurisé | approbation de la procédure opératoire ; scénarios RCV01-A…N | humain avant I2 |
| PERF-01 | campagnes longues, sondage biaisé et charge monitoring | protocole/plateaux/seeds/précision et coût ratifiés avant I3 | humain avant I3 |
| O-01 | métriques trompeuses/cardinalité | tests de cohérence, labels interdits, sampling explicite | équipe L6 |

**Arbitrages humains nécessaires avant GO d'implémentation :**
1. **Procédure physique REC-01** : privilégier arrêt contrôlé et vérifié de tous les writers READ avec READ indisponible ; alternative fencing permanent = complexité supplémentaire injustifiée sans échec démontré du contrôle exclusif. Ratifier l'oracle READY à sept Consumptions et préfixes B déjà normatif, non le redéfinir.
2. **Instrumentation** : réutilisation Micrometer/Actuator/Prometheus et logs corrélés (recommandé) versus tracing distribué additionnel (coût, dépendances, cardinalité). N'ajouter ce dernier qu'après preuve d'une lacune.
3. **Métrologie SPEC-09** : valider les deux configs, protocoles d'échantillonnage, fenêtres, populations et budgets des campagnes, à partir des SLO et volumes normatifs **inchangés**. La disponibilité matérielle A/B conditionne la prétention à un PASS certifié.
4. **Calendrier** : I1 + préparation des scripts en passe groupée, I2 après ratification de la procédure, I3 campagnes après instrumentation et environnements fiables ; réserver G6 majeur à la clôture. Pas de GO implicite de restructuration post-L6.

## 9. Conditions de démarrage et décision de cadrage

Avant implémentation : (a) approbation humaine de ce plan et des protocoles, (b) GO L6 explicite, (c) inventaire précis des points d'instrumentation et du graphe Maven ainsi que des tests existants, (d) budgets de campagne, commande/seed/provenance figés, (e) procédure REC-01 et oracle READY exécutables. **Ce document n'affirme aucune exécution de test, aucune mesure physique ni aucune homologation.**

**Verdict documentaire proposé :** `L6_SCOPING_READY_FOR_HUMAN_REVIEW`. Aucune obligation REC-01, OPS-01, O-01 ou PERF-01 n'est acquittée par le cadrage.
