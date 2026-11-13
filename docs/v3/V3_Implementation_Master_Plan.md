# Pocoma V3 — Plan directeur de réalisation L0–L8

**Cadrage infrastructure A/B — 13 novembre 2026 : `GO_L7_I4_AB_INFRASTRUCTURE_SCOPING`.** [Conception](L7_I4_AB_Infrastructure_Scoping_and_Design_Report.md), [contrat reconstitué](L7_I4_AB_Infrastructure_Contract_Reconstruction.md), [matrice](L7_I4_AB_Infrastructure_Requirements_Matrix.csv), [inventaire](L7_I4_AB_Infrastructure_Inventory.md). Verdict documentaire `READY_WITH_EXPLICIT_DECISIONS` : laboratoire temporaire proposé, deux VM métier4vCPU/8GiB, auxiliaires isolés et passages A/B séquentiels. Aucun GO de création/dépense ni campagne dans cette mission ; protocole SPEC-10 à ratifier avant mesure. Les statuts de certification ci-dessous restent inchangés ; L7.5 BLOCKED, L8 non engagé.

**Mandat courant L7.4 — 13 novembre 2026 : `GO_L7_I4_TECHNICAL_EXECUTION_WITH_I3_CERTIFICATION_DEFERRED`.** Exécution technique autorisée avant certification L7.3. Banc E1 validé et reprise après crash après admission qualifiée sur Command/Registration ; une obligation E02-J proposée à clôture, sans promotion automatique. Les 53 obligations initiales L7.3 restent officiellement 38 clôturées/15 partielles. [Rapport L7.4](L7_I4_SPEC09_Technical_Qualification_Report.md), [contribution L7.3](L7_I4_Contribution_to_L7_I3_Report.md). L7.3 NOT_CERTIFIED, L7.4 TECHNICAL_EXECUTION_AUTHORIZED, L7.5 BLOCKED, L7 OPEN.

**État courant L7.2–L7.4.** Link public retiré ; L7.2 certifié avec réserves. Les anciennes gates de 1 057 tests restent attribuées à leurs SHA ; aucun nouveau full reactor dans la mission technique. SPEC-09 contractuel NOT_RUN : les deux machines dédiées A/B ne sont pas disponibles, absence de cible distante confirmée. Les essais E1 locaux ne certifient aucun SLO. F01-E conserve `/me/pots` avec nouveau binding et p95≤500ms. [Matrice contractuelle](L7_I4_SPEC09_Contract_Matrix.csv), [banc et instrumentation](L7_I4_Bench_Architecture_and_Instrumentation.md), [registre](L7_I4_Anomalies_and_Reservations_Register.md).

**Optimisation expérimentale contrôlée des handoffs asynchrones L6/I3-R7 du 8 novembre 2026 : Analyse causale stricte (A..E), mesure de charge à vide, conformité SPEC-09 et configuration qualifiée (RETAIN_PARTIAL_OPTIMIZATION — I3 DEMEURE OUVERT POUR ARBITRAGE HUMAIN).** [Rapport d'optimisation L6/I3-R7](L6_I3_R7_Controlled_Async_Performance_Optimization_Report.md) ; [rapport R6](L6_I3_R6_Async_Stage_Handoff_Latency_Qualification_Report.md) ; [plan consolidé L6](L6_Design_and_Verification_Plan.md). L6 est OPEN ; I3 a été instruit sous mandat `GO_L6_I3_R7_CAUSAL_PERFORMANCE_OPTIMIZATION` sur 5 configurations orthogonales (A: Baseline originale 10/1000/1000, 4/1, 32/60s ; B: Polling 20ms isolé ; C: Concurrence 4/8 isolée ; D: Batch 32/Claim 120s isolé ; E: Combinée 20ms+4/8+32/120s). Audit de sécurité R6 réalisé : assainissement des identifiants métier sur `/actuator/stage-handoffs` pour les requêtes anonymes (4/4 tests `StageHandoffTrackerTest` PASS). Analyse causale établie : le Polling 20ms réduit causalement le délai de propagation READ de 467 à 109-159 ms (-66% à -75%) mais quadruple les cycles à vide (131 à 554 cycles/s, commits SQL x3.8, CPU Postgres de 15% à 29%) ; la Concurrence 4/8 en isolation dégrade la latence (+149%) et divise le débit par deux par endormissement sur baux par défaut, mais s'avère indispensable en synergie avec le polling 20ms (Config E) pour porter le débit utile sur Pots moyens de 22 à 53 commandes (+141%) et diviser l'E2E p50 par trois (2,45 s vs 12,85 s) ; le Batch/Claim 120s est nocif en isolation (-55% de débit) sans gain démontré. Décision finale : `RETAIN_PARTIAL_OPTIMIZATION` (Polling 20ms CONSERVÉ, Concurrence 4/8 CONSERVÉE, Claim 120s REVERTI à 60s), formalisée dans `docker/monolith/configurations/config-qualification-r7.properties`. Sur Pots moyens SPEC-09 (20 SH, 200 dépenses), la latence E2E p95 est abaissée de 23,4 s à 13,4 s mais n'atteint pas le SLO strict (≤ 500 ms) en raison de l'attente amont SQL (`stageA_commandWait` ~ 2,3 s à 9,5 s représentant 98,9% de la latence). Une unique proposition architecturale prioritaire est formulée pour L7/R8 : notification réactive push via PostgreSQL `LISTEN / NOTIFY` ou in-process Spring events pour éliminer la latence d'attente amont. Budget local R7 strictement respecté : 1 974,9 s / 2 400 s (82,3% consommé, 425,1 s résiduelles).

**Qualification de charge métier réaliste 50 cmd/s L6/I3-R5 du 8 novembre 2026 : Caractérisation sous mix métier 88/10/2 et sérialisation par Pot (REALISTIC_WORKLOAD_QUALIFIED — CAPACITY_LIMIT_CHARACTERIZED — I3 DEMEURE OUVERT POUR DÉCISION HUMAINE).** [Rapport de qualification L6/I3-R5](L6_I3_R5_Realistic_Business_Workload_Qualification_Report.md) ; [rapport final R4](L6_I3_R4_Final_Metrology_and_Sustainable_Capacity_Qualification_Report.md) ; [plan consolidé L6](L6_Design_and_Verification_Plan.md). L6 est OPEN ; I3 a été qualifié sous charge réaliste sous mandat `GO_L6_I3_R5_REALISTIC_BUSINESS_WORKLOAD_50_CPS` : ordonnanceur de charge réaliste (`v3_realistic_workload.py`) modélisant 88% de créations de dépenses, 10% de corrections financières et 2% d'opérations rares (poids shareholders et rattachements SELF), sérialisation stricte des mutations par Pot (au plus 1 commande en vol par Pot avec attente de version terminale confirmée), suppression intégrale de la contention CAS artificielle des campagnes précédentes (0,0% rejets en M1, 2,3% en M2, 1,7% en M5 contre 85,4% sous pipelining spéculatif R4). Capacité utile durable du mono-worker caractérisée à ~5,0 cmd/s appliquées (M2: 4,87 cmd/s appliquées, 97,0% succès, p50 e2e = 1,47 s, 0 starvation). Sous consigne cible à 50 cmd/s (M5, 250 Pots), le système est gouverné par la loi de Little ($N_{\text{pots}} / W_{\text{terminal}} \approx 250 / 30\text{s} = 8,3\text{ cmd/s}$ plafond théorique), atteignant 5,59 cmd/s admises et 4,49 cmd/s appliquées avec 561 commandes appliquées sans dérive de projection READ ni accumulation incontrôlée. Budget persistant R5 : 772,1 s / 1 800 s. G6 PENDING.

**Correction métrologique finale et qualification de capacité utile durable L6/I3-R4 du 8 novembre 2026 : Résolution outillée intégrale des anomalies F-01 à F-07 et qualification de capacité (READY_FOR_HUMAN_CAPACITY_DECISION — I3 DEMEURE OUVERT).** [Rapport final L6/I3-R4](L6_I3_R4_Final_Metrology_and_Sustainable_Capacity_Qualification_Report.md) ; [rapport d'audit indépendant R3](L6_I3_R3_Independent_Capacity_and_Metrology_Audit.md) ; [plan consolidé L6](L6_Design_and_Verification_Plan.md). L6 est OPEN ; I3 est AUDITED & METROLOGY RESOLVED sous mandat `GO_L6_I3_R4_FINAL_METROLOGY_AND_USEFUL_CAPACITY_QUALIFICATION` : observateurs concurrents (12 threads terminal, 8 threads READ) résolvant F-01 (91 000+ sondes HTTP sans famine), sonde de backlog double axe (`v3_command` amont vs `consumption_slot` tampon softCap 256) résolvant F-02, réconciliation SQL exhaustive prouvant l'exactitude des équations de conservation ($2\,965 = 1\,176 + 1\,789$ en Q5), capacité d'admission WRITE validée à $\ge 45.61\text{ cmd/s}$ ($98,83\%$ 202, $p_{50} = 1.06\text{ ms}$), capacité de traitement durable du mono-worker qualifiée à $12-15\text{ trans/s}$ en drainage, débit utile en boucle fermée à $100\%$ de succès ($3.4\text{ cmd/s}$, 102/102 appliquées, 0 rejet CAS) élucidant l'effet de l'interclassement UUID (`ORDER BY request_id` dans `JdbcCommandDiscoveryReferences.java:47`) sous pipelining spéculatif. Budget persistant R4 : 1 028,1 s / 1 800 s. G6 PENDING.

**Audit indépendant métrologie et capacité réelle L6/I3-R3 du 7 novembre 2026 : Requalification de la chaîne asynchrone et identification des artefacts métrologiques (NOT_READY_FOR_CLOSURE — METROLOGY_CORRECTIONS_REQUIRED — SUPERSEDED BY R4).** [Rapport d'audit indépendant L6/I3-R3](L6_I3_R3_Independent_Capacity_and_Metrology_Audit.md) ; [rapport de requalification R2](L6_I3_R2_Metrology_and_Capacity_Requalification_Report.md) ; [plan consolidé L6](L6_Design_and_Verification_Plan.md).

**Requalification métrologique et capacité réelle L6/I3-R2 du 7 novembre 2026 : Injection découplée, réconciliation métrologique et caractérisation de capacité (L6_I3_R2_METROLOGY_REQUALIFIED — SUPERSEDED BY R3 AUDIT).** [Rapport de requalification métrologique et de capacité L6/I3-R2](L6_I3_R2_Metrology_and_Capacity_Requalification_Report.md) ; [rapport d'étape I3-R](L6_I3_Deep_Instrumentation_and_Load_Qualification_Report.md) ; [plan consolidé L6](L6_Design_and_Verification_Plan.md). L6 est OPEN ; I1 et I2 sont CLOSED WITH EXPLICIT RESERVATIONS ; I3-R2 a exécuté sous mandat `GO_L6_I3_R2_METROLOGY_AND_CAPACITY_REQUALIFICATION` le découplage strict du banc (harnais 3 tiers k6/Coordinateur/Observateurs), le renouvellement proactif des tokens Keycloak PKCE/refresh, la réconciliation unitaire de l'ensemble des compteurs et durées I3-R, 5 paliers progressifs P1..P5 (1 à 50 cmd/s sur 120s) certifiés `LOAD_GENERATOR_VALID` avec 0 dropped iterations, la caractérisation de la capacité d'admission WRITE ($\ge 47.4\text{ cmd/s}$) et de la capacité durable du mono-worker ($\approx 5.0 - 5.4\text{ cmd/s}$), l'observation dynamique du backlog PostgreSQL, l'audit algorithmique confirmant la complexité $\mathcal{O}(E \times P)$ de `PotBalanceCalculator`, 26 tests de banc unitaires PASS et 32 oracles PASS. Budget global consommé : 3 583s / 4 200s (59,7 / 70 min plafond). G6 PENDING.

**Complément de mission L6/I3-R du 7 novembre 2026 : Renforcement de l'instrumentation et qualification de performance sous charge (L6_I3_DEEP_QUALIFICATION_COMPLETED — SUPERSEDED BY R2).** [Rapport d'instrumentation approfondie et de qualification de charge L6/I3-R](L6_I3_Deep_Instrumentation_and_Load_Qualification_Report.md) ; [rapport d'implémentation initial L6/I3](L6_I3_Implementation_and_Performance_Qualification_Report.md) ; [plan consolidé L6](L6_Design_and_Verification_Plan.md).


**Décision humaine L6 du 6 novembre 2026 : I1 Observabilité CLOS avec réserves explicites ; I2 Correctifs finaux de sûreté et requalification ciblée (L6_I2_FINAL_SAFETY_CORRECTED_TARGETED_PASS — READY FOR HUMAN REVIEW).** [Acte de clôture I1](L6_I1_Acceptance_and_Closure_Decision.md) ; [plan consolidé L6](L6_Design_and_Verification_Plan.md) ; [plan technique I2 consolidé](L6_I2_Technical_Implementation_Plan.md) ; [rapport de cadrage I2 consolidé](L6_I2_Scoping_and_Design_Report.md) ; [rapport d'implémentation et vérification I2](L6_I2_Implementation_and_Verification_Report.md) ; [rapport post-audit et requalification I2](L6_I2_Post_Audit_Corrections_and_Requalification_Report.md) ; [rapport des correctifs finaux de sûreté I2](L6_I2_Final_Safety_Corrections_and_Requalification_Report.md). L6 est OPEN ; I1 est CLOSED — LOCAL OPERATIONAL V3 PASS WITH EXPLICIT RESERVATIONS ; I2 (« Exploitation & reconstruction READ ») a été corrigé et requalifié sous mandats `GO_L6_I2_POST_AUDIT_CORRECTIONS` et `GO_L6_I2_FINAL_SAFETY_CORRECTIONS` (résolution exhaustive des constats AUD-01 à AUD-19 dont CAS atomique sur `public.read_maintenance_state`, exclusion mutuelle par session advisory lock PostgreSQL, factorisation de la validation terminale READY, procédure de reprise physique non destructive `--resume` refusant tout DROP, procédure de récupération destructive explicite `--recover-destructive`, sécurisation stricte de `--reset-failed` interdisant tout contournement d'oracle, équivalence démontrée de l'oracle $\mathcal{P}_{\text{READY}}(B)$ sur 28 checkpoints et 0 slots résiduels, isolation stricte du rôle `pocoma_read` sans mutation WRITE prouvée par erreur 42501, vérification applicative Actuator/HTTP 401) et validé sur ses trois portes ciblées : Gate A 28/28 PASS (1,90s), Gate B 12/12 PASS (27,84s), Gate C 12/12 PASS (63,00s), budget total 92,74s $\le$ 480s plafond. Soumis pour revue humaine. I3/G6 restent non autorisés. Obligations O-01, REC-01, OPS-01, PERF-01 maintenues ; optimisations Q5-C toujours différées.

**Décision humaine courante du 6 novembre 2026 : L5 — CLOSED WITH EXPLICIT RESERVATIONS — GLOBAL CERTIFICATION PASS, Q5-C AUDIT ACCEPTED, OPTIMIZATIONS DEFERRED UNTIL AFTER L6.** L’[acte de clôture formelle L5/Q5-D](L5_Q5D_Formal_Closure_Decision.md) clôt formellement le lot L5 suite au succès de la certification globale Q5-B6 sur le SHA immuable `2890d88f6fa94ff2262d08a2809f48512ce27c9d` ([Rapport Q5-B6](L5_Q5B6_Final_Certification_Execution_Report.md) : 61/61 modules SUCCESS, 2 344/2 344 tests PASS, 170/170 Architecture PASS, Bruno B5 70/70 PASS, durée 83 min) et à l'approbation de l'audit indépendant [L5/Q5-C](L5_Q5C_Quality_Performance_and_Restructuring_Audit.md). Arbitrage stratégique : l'ensemble des restructurations et optimisations proposées par Q5-C est différé après L6 ; L6 a pour priorité d'assurer l'observabilité et les mesures du socle V3 dans son périmètre normatif existant ; les réserves (FIND-L4-01 quota Q=2, FIND-L2-01 lease 240s, FIND-L4-03 recalcul O(N), FIND-L1-01 coût openComponent, RES-ENV-01 qualification environnementale, FIND-TRANS-01 rationalisation modules L7, FIND-L2-02 branches d'autorité L7, etc.) sont conservées et assignées à L6 et L7. Le lot L6 est éligible au cadrage (DESIGN PENDING).

**Décision humaine historique du 5 novembre 2026 : L5/I4 — CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS.** L’[acte autonome I4](L5_I4_Acceptance_and_Closure_Decision.md) accepte l'invitation complète, la persistance atomique, le chiffrement AES-GCM et la désaffiliation volontaire sans perte d'historique financier. [Rapport d'implémentation](L5_I4_Implementation_Report.md), [rapport post-audit](L5_I4_Post_Audit_Correction_Report.md), [preuves archivées](../testing/evidence/L5-I4/README.md). Preuves locales reprises sans rejeu : **17 méthodes JUnit vérifiées avec XML archivé, 5 méthodes WRITE historiques rapportées (XML écrasé lors du run ciblé), 4 scénarios Bruno B401–B404 PASS (11,7s)**. Réserves **R-I4-01…05** (sensibilité temporelle résiduelle du test d'expiration, preuves XML écrasées, TEST_RUN_SHA des runs initiaux) et réserves héritées L1–L4/I1–I3 maintenues. Gates Architecture globale, full reactor natif unique G5, Bruno B5 et certification globale SPEC-10 **NOT RUN / DEFERRED — obligations non acquittées**. **L5 — OPEN ; Q5 DÛ.** Aucun lancement Q5, aucune certification SPEC-10 globale ni homologation production, aucune qualification au SHA documentaire.

**Décision humaine historique du 5 novembre 2026 : L5/I3 — CLOSED — TARGETED LOCAL PASS WITH EXPLICIT RESERVATIONS.** L’[acte autonome I3](L5_I3_Acceptance_and_Closure_Decision.md) accepte le Link headless simplifié publié à `0d415a6761476d2edba2ed0ae59e5d12a1e8c1ec` : **E1 propose, E2 accepte, Identity WRITE décide**. [Rapport historique](L5_I3_Execution_Report.md), [plan et actualisations](L5_I3_Simplified_Headless_Link_Analysis_and_Plan.md). Preuves reprises sans rejeu : **FAST 13/13 PASS ; PostgreSQL/HTTP 25/25 oracles par preuve composite (passage 25 tests historiquement FAIL) ; Bruno Keycloak réel 10/10, 70 requêtes ; 165,687 s**. Historique **91 invocations JUnit, 86 réussites, 3 failures, 2 errors, 0 skip** conservé. Réserves **AUD-I3-01/02/03 et R-Q1…R-Q5**, avec celles de L1–L4/I1/I2 ; croissance/rétention et capacité à instruire notamment L6/Q5. Création/admission déclaratives, sans consultation CurrentBinding à la création ni contrôle WRITE synchrone. Gates WEB+COMMAND non filtrée, Architecture et full **NOT RUN / DEFERRED — obligations non acquittées** ; Q5 reste dû. **L5 — OPEN ; I4 — ELIGIBLE FOR FRAMING, NO GO**, sous cadrage Invitation, D5/D6, contrat de résultat, déclaration de qualification et décisions humaines propres. Les réserves I3 sans impact sur I4 ne le bloquent pas artificiellement. Aucun lancement I4/Q5, aucune certification SPEC-10 globale ni homologation production, aucune qualification au SHA documentaire.


**Décision humaine historique du 5 novembre 2026 : L5/I2 — CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS.** L’[acte autonome I2](L5_I2_Acceptance_and_Closure_Decision.md) accepte les onze commandes Pot publiques, Registration/Unlink et leurs admissions asynchrones dans la qualification locale bornée. [Preuves initiales](L5_I2_Execution_Report.md) livrées à `df0c3a221ce20b4eb569616d7df621fa4090a32b` : C-04 2 291 PASS dont 170 Architecture, T2 85 identités, B2 24 scénarios/468 requêtes et onze reçus Pot. [Correctif post-audit](L5_I2_Post_Audit_Correction_Report.md) livré à `1fc07000e586394298b2f2cf7acbd5e84ff1c5e6` : 9/9 PASS PostgreSQL, 17,252554 s ; aucun rejeu Architecture, terminal complet ou full reactor. Aucune preuve initiale n’est fraîche au SHA corrigé. R-I2-01 historique et R-CLO-I2-01…04 restent ouvertes avec les réserves L1–L4/I1. Statuts de transition I3/I4 de cette décision I2 : historiques ; les statuts courants sont fixés par l’acte I3 en tête du document. Aucun certificat SPEC-10 global ni GO production.

Pour I3/I4, la décision humaine reporte la preuve Architecture globale à Q5 sans rejeu automatique. Toute obligation normative déclenchée reste **REQUIRED — NOT RUN — DEFERRED TO Q5 BY EXPLICIT HUMAN INSTRUCTION**, avec traçabilité au [plan global §12](L5_Supra_Entry_Audit_and_Implementation_Plan.md#12-incréments-dépendances-et-déclarations-de-vérification) et dans leurs déclarations futures ; aucune exemption implicite. Full natif Q5 toujours obligatoire sous couverture effective G5/Architecture ; B5 et preuves externes restent dus. Synchronisation **DOCUMENTATION ONLY — SLICES NONE**, franchissement NONE, Architecture/full NOT REQUIRED / NOT RUN pour cette publication, aucun test applicatif.

**Décision humaine du 4 novembre 2026 : L5/I1 — CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS.**
L’[acte autonome de clôture I1](L5_I1_Acceptance_and_Closure_Decision.md) accepte le checkpoint interne de la livraison `a2c83d3e9ea651b8cb3015692fede6652677ddfd`, sources qualifiées `46b94ce69a4b0f42474d38ac10b26daa78bb6b23`, départ I1 `f46d63a9bc4505c43610376eeba40a5854431230`. [Rapport I1](L5_I1_Execution_Report.md) et [manifeste figé](L5_I1_Proof_Manifest.json) : T1 40/40, reprise ciblée 63/63, B1 32/32 (66 requêtes), Architecture native non filtrée 2 231 PASS, sans rejeu. **L5/I2 — CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS**, selon l’[acte courant](L5_I2_Acceptance_and_Closure_Decision.md) ; [rapport technique et preuves](L5_I2_Execution_Report.md), avec attribution distincte initiale/post-audit. **L5 reste OPEN**, sans certification globale SPEC-10 ni qualification de production ; EXTERNAL AUTHN NOT QUALIFIED. R-I1-01…04 et réserves L1–L4 conservées. D6 : full natif unique dû à Q5 sous couverture effective G5/Architecture ; B5 et preuves externes restent dus indépendamment. Les transitions antérieures ci-dessous sont historiques.

**Décision humaine du 3 novembre 2026 : L4 — READ Projections : CLOSED WITH EXPLICIT RESERVATIONS — COMPOSITE INTERNAL QUALIFICATION ACCEPTED.**
L’[acte autonome de clôture](L4_Read_Closure_Decision.md) porte l’acceptation interne du dossier I8
`70ff3e53526d902cfba141cf11385e340138503d`, l’avis indépendant favorable sans BLOCKER ni MAJOR,
les réserves et les conditions d’entrée de L5. Six participants activables dans PROJECTION et
Monolithe, façades internes READ et T01–T11 PASS. Qualification composite acceptée :
**2 024 tests applicatifs hors Architecture PASS** au SHA `a3470df662f4552d05785ab3ec191951c4cc9d56`
+ **167 tests Architecture PASS** au SHA `8a007768c23021153764473f6c6686042168df00`, sans différence productive/POM/SQL.
**Aucun full natif PASS** : trois full terminés FAIL, quatrième interrompu par dispense humaine explicite.
**Aucune certification SPEC-10 globale.** Lease180 et dimensionnement de production non homologués ;
`AUD-FINAL-I8-01` (prévalidation pure de `expenses`/`visible` avant quota READ) reste MINOR non bloquant en suivi.
[Rapport I8](L4_I8_Execution_Report.md), [manifeste historique](L4_I8_Proof_Manifest.json),
[preuves figées](../testing/evidence/L4-I8/README.md) et [plan I8](L4_I8_Implementation_and_Qualification_Plan.md).
Les réserves historiques I1/I2/I3–I7, recovery et L2/L3 ainsi que les obligations L5/L6/L7 sont conservées.
**L5 : cadrage possible après publication vérifiée de l’acte, sous son propre plan et ses décisions ; aucun GO d’implémentation.**
Les transitions et attentes antérieures ci-dessous sont historiques ; elles ne rouvrent aucun incrément.

**L4/I2 — CLOSED — LOCAL BOUNDED PASS — 3 novembre 2026.**

La décision humaine explicite du 3 novembre 2026 acte la clôture définitive bornée de I2.
`I2-QUAL-01` est RESOLVED ; aucun défaut de production n’a été identifié dans sa résolution
et aucune réserve bloquante I2 ne subsiste. Les deux gates canoniques déjà acceptées sont
PROJECTION + COMMAND : **1873/0/0/0**, Architecture : **2137/0/0/0** (tests/failures/errors/skips),
au commit applicatif `6496e6d0144ecfda9e58ec6d12e259445e7344ad`, avec publication des preuves
`2a81d49ebeb595cc2c46f14b3d850cbf92863f93`. Ces résultats sont acquis et ne sont pas rejoués.
La clôture I2 est indépendante du correctif du helper L3/I2 `IdentityPotAuthorityPostgresTest#waiterPid`.
Les échecs, corrections, contre-oracles et réserves historiques restent attribués à leurs campagnes.
Cette décision ne certifie ni SPEC-10 global ni la clôture finale de L4 ; L4 reste ouvert.
[Rapport et décision I2](L4_I2_Execution_Report.md#clôture-définitive-du-3-novembre-2026),
[plan I2](L4_I2_Implementation_Plan.md), [manifestes/preuves historiques](L4_I2_Proof_Manifest.json).
La requalification globale est reportée à la clôture L4 : I1–I7, régressions L2/L3,
correctif `waiterPid`, activation I8 et gates politique/SPEC-10 ; son périmètre, ses campagnes,
coûts indicatifs et critères de diagnostic sont fixés dans le [plan I8](L4_I8_Implementation_and_Qualification_Plan.md), sans plafond mural pour une exécution saine.
Voir le [plan global L4](L4_Read_Inventory_and_Implementation_Plan.md#qualification-globale-reportée-à-la-clôture-l4).
Les décisions antérieures de transition et de cadrage ci-dessous restent historiques.


**L4/I1 — décision humaine définitive du 2 novembre 2026 :
CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS.**
L’[acte de clôture](L4_I1_Acceptance_Decision.md) enregistre l’acceptation bornée
au dossier `3bba9dbaa4fedfe040ed9f7e5d7ca2239b1ddab7`. Le verdict
d’implémentation **IMPLEMENTED — LOCAL BOUNDED PASS** et les PASS locaux restent
attribués au [rapport](L4_I1_Execution_Report.md) et au
[manifeste](L4_I1_Proof_Manifest.json) : I1 37/0/0/0, FOUNDATION 29/0/0/0,
PROJECTION + COMMAND 1833/0/0/0, Architecture isolée 2096/0/0/0 dont 164 dans
le module Architecture. Full reactor non requis/non exécuté.
Les quatre réserves sont conservées : preuves PostgreSQL archivées sans rejeu
physique par l’audit indépendant ; FAIL Architecture initial et diagnostic WEB
avec qualification isolée ultérieure ; mémoire du codec et volumes à examiner
aux incréments métier sans défaut I1 établi ; poisoning tardif L2 corrigé à
préserver et analyser en régression I2. Aucun nouveau test ni audit dans cet acte.

**L4/I2 peut désormais être engagé après son propre cadrage technique** :
PreparationReads, snapshots source/effets séquentiels, incertitude irréversible
possédée par L2, préparations Command/Identity préservées, recovery
checkpoint/slot/preuves, aucune transaction distribuée et requalification ciblée
L2, incluant le poisoning tardif I1. Aucun nouveau plan ni code I2 dans cette
mission. **L4 reste ouvert** ; préparation I2, matérialisations I3–I7, activation
I8, qualification globale L4 et contrats L5/L6 ne sont pas certifiés par I1.
D1–D4 et les réserves historiques L2/L3 restent acquis.

**Ratification humaine du 1er novembre 2026 — L3 — Identity WRITE Engine : CLOSED WITH EXPLICIT RESERVATIONS.**

L’[acte de clôture unique](L3_Identity_Closure.md) consigne la décision explicite du
responsable du programme, l’avis favorable O25 transmis dans le mandat et ses limites.
Baseline documentaire examinée : `ece3f5057808c4c99afd31d276acf043fe7872b4`.
O20 et O25 : **SATISFIED WITH EXPLICIT RESERVATIONS** ; O21 : **SATISFIED WITH RESERVATIONS**.
La classification `OPTIONAL_EXPLORATORY_PERFORMANCE` et la recevabilité du SKIP de
`TerminalSequencingPostgresTest#zExploratoryContentionSeparateFromCorrectness`
sont ratifiées nominativement pour la seule qualification interne L3/I5.
O20 **2057/0/0/1** et O21 **1791/0/0/1** restent strictement **INCONCLUSIVE** ;
[archives, compteurs et sources](../testing/evidence/L3-I5/canonical/Campaigns.json) inchangés.
Les FAIL/UNPROVEN antérieurs et toutes les réserves L2/L3 sont conservés.
La clôture est bornée au WRITE Identity qualifié ; aucun certificat global SPEC-10,
de performance, de disponibilité universelle ou de production n’est accordé.
**L4 est autorisé à s’ouvrir au cadrage après publication et vérification de cet acte ;
aucune implémentation L4 n’est autorisée par cette mission.**
L’orientation Fondation/Branchement est à instruire dans le
[plan directeur](V3_Implementation_Master_Plan.md#orientation-à-instruire-au-cadrage-l4--qualification-réutilisable-de-l2-fondation).
La politique Reactor et les gates existantes restent inchangées.
Les encadrés antérieurs ci-dessous sont historiques et ne fixent pas le statut courant.

**Décision humaine historique L3/I4 du 31-10-2026 : DOCUMENTARY CLOSED / ACCEPTED WITH
EXPLICIT RESERVATIONS**, [décision, périmètre, réserves et reprise autonome I5](L3_I4_Acceptance_Decision.md).
Code testé `145f42731a4e35488e9aaa56a7de3c3f453a9231` ; livraison et preuves
`126dc10ef6ac3fc304ad098b5d9dc85a24f40f17` ; [rapport I4](L3_I4_Execution_Report.md),
[manifeste](L3_I4_Proof_Manifest.json), [preuves](../testing/evidence/L3-I4/README.md).
R-ID-POT interne accepté dans les configurations testées, A1 conservé. Contre-audit
Gemini favorable avec réserves selon les conclusions humaines transmises ; rapport
I4 original absent du dépôt, aucun nouveau rejeu PostgreSQL ni gate Maven par Gemini.
FAST 65, CHANGE 149, INTEGRATION 187 et Architecture bornée 163 : PASS bornés,
0 failure/error/skip ; sélection/provenance : 40/0/0/0 PASS. Gates non filtrées :
COMMAND + Monolithe **FAIL (1 789/1/0/1)** sur `i5_r12` ; Architecture
**FAIL (1 892/2/0/1)** sur `i5_r05` et `i5_r12`, module Architecture SKIPPED après
échec amont. Aucun PASS canonique global ; réserves L2/I3, limites de découverte
et d'attribution, absence de full reactor conservées.
**L3/I5 — READY FOR FRAMING ONLY ; NEXT — cadrage/préparation autorisés,
implémentation non autorisée. L3 — OPEN.** Aucune certification globale.
Les encadrés de décision antérieurs restent historiques.

**Décision humaine historique L3/I3 consignée le 31-10-2026 : ACCEPTED WITH EXPLICIT
RESERVATIONS**, [décision, findings, réserves et preuves](L3_I3_Acceptance_Decision.md).
Implémentation et corrections de sécurité acceptées après la
[contre-vérification indépendante Gemini](L3_I3_Independent_Reaudit_Report.md).
**Prochaine étape : cadrage et préparation I4 autorisés**, sans GO d'implémentation.
I5 et les étapes suivantes restent à réaliser selon le plan ; **L3 reste en
réalisation**. Aucune certification canonique globale, aucune levée de
`i5_r05`/`i5_r12` ; Architecture canonique non acquise, full reactor non exécuté.
Cette décision supplante les seuls arrêts procéduraux I3 ci-dessous. Les
encadrés antérieurs conservent les décisions et résultats à leur date.

**Livraison L3/I3 du 30-10-2026 : IMPLEMENTED — R-ID interne démontré.**
[Rapport, gates, commits et limites](L3_I3_Execution_Report.md),
[runbook DEV contrôlé](L3_I3_Identity_Development_Runbook.md).
IdentityRequest est raccordée au vrai moteur L2 pour Registration/Link/Unlink,
avec V6/grants, les deux racines et un exec jar redémarré sans reset. Les 37 cas
I3 sont PASS ; la gate finale est FAIL (`i5_r12`, 1 859 tests, 1 failure, 0 error,
1 skip historique), Architecture SKIPPED. Les FAIL/SKIP/INCONCLUSIVE sont conservés
dans le rapport ;
aucune acceptation indépendante, certification externe, levée des réserves
`i5_r05`/`i5_r12` ou autorisation I4/I5 n'est déduite de cette livraison.
Les formulations « I3 non commencé par cette publication » ci-dessous décrivent
la publication historique de l'arbitrage I2, antérieure à cette implémentation.

**Arbitrage humain L3/I2 du 30-10-2026 : ACCEPTED WITH EXPLICIT RESERVATIONS —
GO borné L3/I3 après publication documentaire**, [décision et réserves](L3_I2_Acceptance_Decision_2026-10-30.md).
Les 27 tests I2 PostgreSQL et les 21 contrôles d'architecture ciblés sont PASS
sur leurs périmètres ; la gate canonique COMMAND + Architecture reste **FAIL
sur `i5_r12`**, Architecture **SKIPPED**. Aucun I2 LOCAL BOUNDED PASS ni PASS
technique canonique ou global n'est prononcé. Le harness I2 ne certifie ni
le participant IdentityRequest ni la composition runtime future. Les acceptations
et réserves L1/L2 restent inchangées ; aucune dispense des gates ultérieures.
I3 n'est pas commencé par cette publication ; I4/I5 restent soumis à leurs GO.
Cet arbitrage supplante les seuls arrêts procéduraux avant I3 de la livraison I2,
sans réécrire les résultats historiques ni les réserves de certification.

**Livraison technique I2 — état historique avant arbitrage ci-dessus.**
**Livraison L3/I2 du 30-10-2026 : I2 IMPLEMENTED, preuves locales satisfaites,
gate canonique FAIL — aucun I2 LOCAL BOUNDED PASS ni GO I3.**
27 tests I2 PASS sur PostgreSQL réel ; gate COMMAND + Architecture :
1 822 tests / 1 failure (`i5_r12`) / 0 error / 1 skip ; Architecture canonique
SKIPPED, contribution ciblée distincte. [Rapport et preuves I2](L3_I2_Execution_Report.md).
Audit indépendant requis avant toute décision I3. L'arbitrage et les échecs
historiques I1, ainsi que les acceptations/réserves L1/L2, restent inchangés.

**Arbitrage historique L3/I1 du 30-10-2026 : ACCEPTED WITH EXPLICIT RESERVATIONS —
GO I2**, [décision, réserves et scope I2](L3_I1_Arbitration_and_I2_GO.md).
L3 est en réalisation : I1 accepté humainement, 35 nouveaux tests PASS, gate
COMMAND + Architecture FAIL (`i5_r05`, `i5_r12`), aucun I1 LOCAL BOUNDED PASS.
I2 est autorisé selon le §6 du plan L3 ; aucune implémentation I2 n'est attestée
par cet enregistrement. I3–I5 non autorisés. Les anciennes mentions DESIGN
PENDING/cadrage L3 et arrêt avant I2 sont supplantées pour la progression ;
les résultats historiques et acceptations L1/L2 restent inchangés.

**Correctifs transverses L1/L2 du 30-10-2026 :** [rapport correctif borné](L1L2_Targeted_Corrections_Report.md). Corrections de l'arité JDBC et de la garde Identity ; observation Discovery traitée sans changement de timeout. **CLOSED WITH EXPLICIT RESERVATIONS** : deux findings FIXED, gate composée COMMAND + Architecture réussie sur le périmètre autorisé ; exclusion humaine nominative des seules campagnes I5 `i5_r05` et `i5_r12`, conservées NOT RUN, SKIP historique et réserve de couverture globale. Aucun développement L3, aucun changement des acceptations L1/L2, de G1/G2/G3 ou des réserves I5.

**Décision humaine du 30-10-2026 : L2 — ACCEPTED WITH EXPLICIT RESERVATIONS, officiellement clos.** [Clôture documentaire unique](L2_Documentary_Closure.md) : I1–I4 CLOSED / LOCAL BOUNDED PASS (réserves I3 conservées), I5 CLOSED BY HUMAN ACCEPTANCE WITH AVAILABILITY RESERVE, I6 COMPLETED ; I7 est une revue indépendante favorable sur le périmètre examiné, non exhaustive. **G1/G2 INCONCLUSIVE ; G3 NOT PASSED / NOT COMPLETED.** Acceptation humaine distincte de PASS technique global ; R-L2-01 à R-L2-05 ouvertes, aucun GO production ni certificat de release V3. **Passage à L3 autorisé sous son propre cadrage, ses critères de vérification et ses décisions d'implémentation ; prochaine intervention : cadrage L3 — Identity uniquement.** Cette décision prévaut pour les statuts de programme sur les mentions historiques ci-dessous, sans réécriture des résultats ni réouverture d'I5.

Date : 2026-10-25. Référence inventoriée : `f50a413ee200a60b7747107abf96b62353e769e0`, branche `feat/v3-architecture`.

**Décision humaine du 26-10-2026 : G0 franchi, I1 autorisé.** Le responsable lève explicitement la réserve procédurale d’audit indépendant L1-F2 et accepte les preuves existantes avec leurs limites documentées. Aucun rapport historique n’est modifié ; aucune certification globale, aucun PASS technique I1 ni G1/G2/G3 n’en découle. [Dossier G0 courant](L2_I1_G0_Readiness_and_Implementation_Plan.md#décision-courante--g0-franchi-le-26-octobre-2026).

**Point d’entrée opérationnel unique du programme. L0 : ACCEPTED pour la poursuite. L1 : ACCEPTED — internal V3 foundation. L2 : ACCEPTED WITH EXPLICIT RESERVATIONS par décision humaine du 30 octobre 2026 ; [statuts, preuves, réserves et transition](L2_Documentary_Closure.md). L3 : CLOSED WITH EXPLICIT RESERVATIONS, décision humaine du 01-11-2026 ; L4 : CLOSED WITH EXPLICIT RESERVATIONS — COMPOSITE INTERNAL QUALIFICATION ACCEPTED selon l’[acte du 03-11-2026](L4_Read_Closure_Decision.md) ; L5 : CLOSED WITH EXPLICIT RESERVATIONS — GLOBAL CERTIFICATION PASS, Q5-C AUDIT ACCEPTED, OPTIMIZATIONS DEFERRED UNTIL AFTER L6 selon l'[acte de clôture formelle Q5-D du 06-11-2026](L5_Q5D_Formal_Closure_Decision.md) ; L6 : OPEN (I1 & I2 CLOSED WITH EXPLICIT RESERVATIONS, I3 éligible au cadrage technique) ; L7–L8 : DESIGN PENDING.** **L1-F CLOSED / PASS interne** : corrections post-audit et preuves dans le [rapport L1-F](L1_F_Closure_Report.md). **L1-F2 CLOSED / PASS interne**, complément correctif WRITE distinct depuis `b3df3f3e`, avant L2. [Inventaire](L1_F2_Contradictory_Inventory.md), [matrice](L1_F2_Invariant_Matrix.md), [rapport](L1_F2_Closure_Report.md). Le responsable a donné son **GO humain d'implémentation le 26-10-2026** et ratifié neuf UUID/`P=4` pour le pilote. L'audit L1-F2 historique a été retrouvé ; son PASS a été contesté par le [contre-audit](L2_Independent_Counter_Audit_and_G0_Assessment.md#8-l1-f2-axes-daudit-et-actions). La réserve de recevabilité est levée par la décision humaine ci-dessus, avec les limites conservées. Aucun nouvel audit L1-F2 requis pour I1 ; les verdicts de clôture G1/G2/G3 figurent ci-dessus.

## 1. Autorités, vision et périmètre

Le [canon](V3_Canon_and_Roadmap.md) porte les décisions normatives transversales et renvoie aux [SPEC-01 à SPEC-10](V3_Canon_and_Roadmap.md#3-spécifications-normatives-arb-08). Ce plan porte le programme, les dépendances et les statuts ; les plans de lot portent la conception et l’exécution détaillées ; les rapports et manifestes attestent les réalisations. Une modification du programme ne modifie pas un invariant des SPEC. Une contradiction métier exige un arbitrage dans son autorité normative avant code.

Pocoma gère des Pots partagés : créateur immuable, Shareholders indépendants de leur rattachement identitaire, Expenses et soldes rationnels exacts. Huit composantes possèdent leurs révisions locales ; une mutation effective produit une seule version globale Pot, reconstructible directement à V. Les suppressions sont logiques et conservent l’histoire. Les balances actives sont complètes à V, sans calcul différentiel depuis V−1.

La chaîne cible sépare admission durable WRITE, exécution métier, événements immuables, Consumption et consultation READ. Discovery enrôle les références ; Engine acquiert et exécute une tentative complète par claim. Les slots/checkpoints résident dans la même autorité physique que leurs effets. WRITE et READ ne partagent aucune transaction distribuée. Les trois Consumptions de POT_EVENT sont directes et indépendantes ; aucune tâche intermédiaire Balance/Visibility. Les sept familles, cinq séquences, neuf projections et neuf Consumptions logiques des SPEC ne prescrivent pas un nombre de tables, processus ou conteneurs.

Identity possède PocomaUser et EUI versionnée : EUI → 0..1 User, User → 0..N EUI, Binding relationnel et bindingToken renouvelé à chaque occurrence. Authentification externe, token et droits métier sont distincts. Entitlements et consentement Link sont contrôlés à l’admission ; autorité Pocoma courante revalidée en WRITE. READ autorise à V avec CurrentBinding actif ; navigation non autorisante, résultats appartenant à requesterEui. Le serveur d’autorisation reste à qualifier, Keycloak compris.

Architecture hexagonale : domaine pur, engines orchestrant des ports explicites, adaptateurs techniques, compositions par injection de constructeurs. Chaque abstraction, conversion, mécanisme ou persistance doit répondre à un invariant réel. Réutiliser le savoir-faire historique sans recopier les regroupements V2 ni créer des passerelles de compatibilité fictives. Traçabilité scénario → méthode → commande → SHA testé → artefact → publication → audit ; aucun succès Maven ne remplace la certification fonctionnelle, technique ou de performance.

À la livraison finale, une seule implémentation active de référence existe : **Pocoma**. Le chantier de convergence du §6 retire la coexistence transitoire V2/V3. Les preuves historiques restent conservées.

## 2. Origine du découpage et statuts

Le corpus courant approuve L0, L1-A…E et les jalons conceptuels V3-CADRE, V3-6 (réalisation), V3-7 (qualification). Lors de la rédaction initiale de ce plan, aucun plan directeur L0–L8 ni plan détaillé L2–L8 n’avait été trouvé dans les fichiers suivis. Le dossier L2 a ensuite été approuvé : G0 franchi le 26-10-2026, I1 seul autorisé. La recherche Git du canon et des anciens plans ne fournit pas d’affectation L2–L8 approuvée. L’audit conceptuel ancien évoque « Lot 2 — Séquencement, Slots, Discovery, Engine », « Lot 3 — Identity », « Lots 4 et 5 — READ et Supras » ; ces mentions corroborent un ordre de travail mais ne constituent pas une approbation de plans d’exécution.

La proposition ci-dessous conserve cet ordre et les objectifs approuvés. **L2 est
accepté avec réserves explicites ; L3 est CLOSED WITH EXPLICIT RESERVATIONS selon
l’[acte unique](L3_Identity_Closure.md) du 01-11-2026 ; L4 est définitivement
clos avec réserves et qualification composite interne acceptée selon son
[acte du 03-11-2026](L4_Read_Closure_Decision.md) ; L5 est CLOSED WITH EXPLICIT RESERVATIONS selon l'[acte Q5-D du 06-11-2026](L5_Q5D_Formal_Closure_Decision.md) ; L6 est en cours d'exécution (I1 et I2 clos avec réserves explicites selon les actes [L6_I1_Acceptance_and_Closure_Decision.md](L6_I1_Acceptance_and_Closure_Decision.md) et [L6_I2_Acceptance_and_Closure_Decision.md](L6_I2_Acceptance_and_Closure_Decision.md) ; I3 implémenté et qualifié localement sur banc borné selon le [rapport I3](L6_I3_Implementation_and_Performance_Qualification_Report.md)) ; G6 et L7–L8 restent à exécuter.** L7 convergence/certification
et L8 distribution gardent leurs obligations ; V3-7 reste le jalon transversal
auquel contribuent L6/L7/L8. Aucun certificat global ni GO production n’est accordé.

| Statut de programme | Signification |
|---|---|
| NOT STARTED | Travail identifié, préparation non engagée |
| DESIGN PENDING | Périmètre/plan proposé, décisions ou revue encore nécessaires ; code non autorisé |
| DESIGN APPROVED | Plan audité et validé ; autorisation d’implémentation à enregistrer séparément |
| IMPLEMENTING | Campagne autorisée en cours |
| INTERNAL PASS | Preuves obligatoires internes satisfaites, limites explicites |
| AUDIT PENDING | Dossier publié soumis au checkpoint indépendant |
| ACCEPTED | Acceptation explicite après revue, dans le périmètre attesté |
| ACCEPTED WITH EXPLICIT RESERVATIONS | Acceptation humaine et clôture du programme dans le périmètre déclaré, réserves ouvertes conservées ; aucun PASS technique global implicite |
| BLOCKED | Dépendance, défaut ou arbitrage bloquant identifié et documenté |

PASS/FAIL/INCONCLUSIVE sont des verdicts de preuve, distincts du statut de programme. Chaque passage à ACCEPTED cite sa décision, ses SHAs et ses preuves. Les acceptations L0/L1/L2 ne valent aucune activation ni certificat V3 complet.

## 3. Tableau directeur

Les critères détaillés des §§4–8 font partie de chaque ligne. Pour L4, la clôture interne avec réserves et la qualification composite sont acceptées selon l’[acte humain](L4_Read_Closure_Decision.md), sans full natif PASS ni certification SPEC-10 globale ; L3 distingue sa clôture interne des certifications encore dues. Pour L2, la sortie humaine avec réserves est distinguée des critères techniques non franchis. Les gates proposés doivent être revalidés sur le graphe réel avant code selon le §5.

| Lot / statut | Objectif, périmètre et livrables | Dépendances et critères d’entrée | Critères de sortie, gates et preuves | Références, risques et décisions ouvertes |
|---|---|---|---|---|
| **L0 — ACCEPTED** | Socle de vérification : pureté/direction, banc PostgreSQL isolé, transaction physique JPA/JDBC, intervalles, composition, collecte | V3-CADRE validé, inventaire L0-A et autorisation L0-B…F | 19 méthodes Java obligatoires + 7 tests collecteur PASS rapportés ; FOUNDATION et architecture exécutés ; couverture globale INCONCLUSIVE, full non requis | [Plan](L0_Verification_Plan.md), [inventaire](L0_Technical_Inventory.md), [rapport](L0_Execution_Report.md), [manifeste](L0_Proof_Manifest.json), acceptation pour poursuite [canon §10](V3_Canon_and_Roadmap.md#10-v3-6--socle-l0-2026-10-24). Socle de test, aucune autorité métier certifiée |
| **L1 — ACCEPTED** | Fondation Pot interne : A domaine/transitions, B histoire PostgreSQL, C orchestration/CAS/transactions/reprises, D droits/calcul exact, E intégration/preuves | L0, plan L1 approuvé, checkpoints A/B puis C puis final L1 | 91 méthodes obligatoires PASS selon dossier final ; architecture et full reactor exécutés sur `9899a2943760660893768fa4ba9452732ee17d50` ; A-01 technique, A-02 automatique PASS, I-01 complet L1 ; couverture globale INCONCLUSIVE | [Plan L1](L1_Design_and_Verification_Plan.md), [clôture et attribution](L1_Documentary_Closure.md), [rapport E](L1_E_Execution_Report.md), [manifeste](L1_Proof_Manifest.json). Autorité Identity réelle, HTTP/READ/Consumption/Discovery hors périmètre ; performances non certifiées |
| **L1-F — CLOSED / PASS interne** | Correctifs post-audit L1, sans réécriture A…E : lectures temporelles, INVALID_BINDING, structure des intentions, socle invitation, contraintes statiques, profilage exact, catalogues et préparation L2 | L1 accepté ; baseline `2fb9a536f3894e797923c0b49aeba1a9b5adfda2` ; mandat autonome L1-F | COMMAND+EVENT+PROJECTION 1306/0/0/1, 100 obligatoires PASS ; architecture 1511/0/0/1, 108 obligatoires PASS sur c94e7a4b ; couverture complète INCONCLUSIVE (skip exploratoire V2). PG/EXPLAIN/mesures ; full non requis/non exécuté | [F0 contradictoire](L1_F_Contradictory_Inventory.md), [rapport](L1_F_Closure_Report.md). Parcours Invitation réel toujours L5 ; aucune nouvelle autorité Identity/READ/Consumption |
| **L1-F2 — CLOSED / PASS interne** | Supprimer les chaînes historiques des préconditions WRITE ; accès directs/groupés, fermeture exacte, audit intégral explicite | L1-F historiquement clos ; baseline `b3df3f3e` ; mandat autonome F2-0…7 | COMMAND 1265/0/0/1, 107 obligatoires PASS sur e154276a ; corruptions/concurrence et avant/après 100/1000/10000, 28 lignes Java ; architecture/full non requis/non exécutés | [Inventaire](L1_F2_Contradictory_Inventory.md), [matrice](L1_F2_Invariant_Matrix.md), [manifeste](L1_F2_Proof_Manifest.json), [rapport](L1_F2_Closure_Report.md). Audit avant L2 ; aucune activation |
| **L2 — ACCEPTED WITH EXPLICIT RESERVATIONS** | Fondation Consumption/Discovery livrée : slots/routage/ownership, Engine et participant Command Pot, lifecycle, Discovery, workers et banc READ technique ; I6 documentaire COMPLETED | Décision humaine définitive du 30-10-2026, suffisante pour poursuivre avec les réserves R-L2-01 à R-L2-05 ; invariants SPEC inchangés | I1–I4 CLOSED / LOCAL BOUNDED PASS avec réserves I3 ; I5 CLOSED BY HUMAN ACCEPTANCE WITH AVAILABILITY RESERVE ; I7 favorable sur le périmètre examiné, non exhaustif ; G1/G2 INCONCLUSIVE, G3 NOT PASSED / NOT COMPLETED ; architecture/full de clôture non exécutés sur le gel final | [Clôture officielle, preuves et SHAs](L2_Documentary_Closure.md), [dossier historique I6](L2_I6_I7_Consolidation.md), [plan L2](L2_Design_and_Verification_Plan.md). Aucun PASS technique global, Identity/Invitation réelles et projections métier non certifiées par L2 ; aucun GO production |
| **L3 — CLOSED WITH EXPLICIT RESERVATIONS (01-11-2026)** | Autorité Identity réelle : EUI@version, User, Registration/Link/Unlink, histoire/tokens, exécution durable et événements ; remplacement des fixtures d’autorité L1 | L2 fondations acceptées avec réserves ; [dépendances à reprendre en L3](L2_Documentary_Closure.md#6-exigences-reportées-et-dépendances-pour-l3--identity), contrats SPEC-02 et ordre canonique des EUI ; frontière avec qualification externe L5 explicitée | ID-01 interne, EVT/CON Identity, races à deux EUI, rollback et absence d’User orphelin ; tests Command avec vrai fournisseur d’autorité. I2 : gate canonique FAIL (`i5_r12`), Architecture SKIPPED, aucun I2 LOCAL BOUNDED PASS. I3 accepté avec réserves après corrections et ré-audit ; R-ID interne démontré. Gates non filtrées I3 et résolution FAIL (`i5_r12`), Architecture canonique non acquise ; 232 tests ciblés frais PASS distincts. I4 accepté humainement après contre-audit favorable rapporté ; R-ID-POT interne et A1 acceptés, gates natives FAIL conservées ; I5 : trois probes Q-ID fraîches PASS ; O20/O25 SATISFIED WITH EXPLICIT RESERVATIONS, O21 SATISFIED WITH RESERVATIONS par décision humaine finale ; verdicts O20/O21 strict INCONCLUSIVE conservés ; aucune certification globale | [Plan L3](L3_Identity_Inventory_and_Implementation_Plan.md), [décision I4 et reprise I5](L3_I4_Acceptance_Decision.md), [décision I3 et réserves R1–R4](L3_I3_Acceptance_Decision.md), [arbitrage I2 / GO I3](L3_I2_Acceptance_Decision_2026-10-30.md), [arbitrage historique I1](L3_I1_Arbitration_and_I2_GO.md), [SPEC-02](SPEC-02_Identity_Domain.md), [SPEC-03](SPEC-03_Events_Requests_Consumables.md), [contrat C](L1_C_Transaction_Contract.md). Ne pas certifier ID-02 externe avec fixture ; qualification du consentement préparée ici, prouvée à L5 |
| **L4 — CLOSED WITH EXPLICIT RESERVATIONS — COMPOSITE INTERNAL QUALIFICATION ACCEPTED (03-11-2026)** | READ : six participants indépendants activables dans PROJECTION et Monolithe, façades internes cohérentes à V, Discovery/Consumption L2 et owners réutilisés | Dossier I8 `70ff3e53526d902cfba141cf11385e340138503d`, audit indépendant favorable sans BLOCKER ni MAJOR, décision humaine acquise | T01–T11 PASS ; 2 024 tests applicatifs PASS à `a3470df6…` + 167 Architecture PASS à `8a007768…`, équivalence productive ; aucun full natif PASS, dernier complet FAIL, dispense explicite du quatrième ; aucune certification SPEC-10 globale | [Acte de clôture et entrée L5](L4_Read_Closure_Decision.md), [rapport I8](L4_I8_Execution_Report.md), [plan L4](L4_Read_Inventory_and_Implementation_Plan.md). Lease180/production non homologués ; MINOR AUD-FINAL-I8-01 et réserves historiques maintenus ; HTTP/CurrentInvitation L5, performance/maintenance L6, rationalisation L7 |
| **L5 — CLOSED WITH EXPLICIT RESERVATIONS (06-11-2026)** | Supras et parcours externes complets : AuthN, admission/idempotence, autorisation READ, consentement Link qualifié, invitations WRITE→événement→CurrentInvitation→auto-rattachement ; Bruno de travail certifié | L2/L3/L4 acceptés avec leurs réserves ; I1–I4 complétés avec clôtures locales bornées ; qualification globale Q5 exécutée et certifiée (Q5-B6) ; audit indépendant réalisé (Q5-C) ; décision humaine de clôture formelle acquise (Q5-D) | 61/61 modules Maven SUCCESS, 2 344/2 344 tests PASS, Gate Architecture 170/170 PASS, Bruno B5 70/70 PASS au SHA `2890d88f` ; audit Q5-C accepté ; réserves (FIND-L4-01, FIND-L2-01, FIND-L4-03, FIND-L1-01, RES-ENV-01, FIND-TRANS-01, FIND-L2-02) conservées et attribuées ; optimisations et restructurations différées après L6 | [Acte de clôture formelle L5/Q5-D](L5_Q5D_Formal_Closure_Decision.md), [rapport de certification Q5-B6](L5_Q5B6_Final_Certification_Execution_Report.md), [audit Q5-C](L5_Q5C_Quality_Performance_and_Restructuring_Audit.md), [plan global L5](L5_Supra_Entry_Audit_and_Implementation_Plan.md), [plan Q5](L5_Q5_Final_Qualification_Plan.md). Aucune homologation de production. L6 éligible au cadrage. |
| **L6 — IMPLEMENTING (I1 & I2 CLOSED WITH EXPLICIT RESERVATIONS, I3 BOUNDED LOCAL PASS)** | Intégration exploitable, reconstruction READ exclusive, instrumentation O-01 et préparation/qualification de performance ; dossier complet des exigences sur composants réels | L2–L5 formellement acceptés et clos avec réserves explicites ; priorité absolue observabilité et mesures avant toute restructuration ; protocoles de mesure ratifiés avant campagnes ; I1 Observabilité clos formellement avec réserves ([décision L6/I1](L6_I1_Acceptance_and_Closure_Decision.md)) ; I2 Exploitation et reconstruction READ clos formellement avec réserves ([décision L6/I2](L6_I2_Acceptance_and_Closure_Decision.md)), 7 réserves obligatoires RES-L6-I2-01…07 enregistrées ; I3 bancs et qualification locale bornée exécutés ([rapport I3](L6_I3_Implementation_and_Performance_Qualification_Report.md)) | REC-01/C-02, OPS-01 et PERF-01 F01-A…J sur environnements fixés ; Bruno/JUnit/PG/k6, données brutes, métriques validées et défauts traités ; gates slices réelles, architecture/full au jalon majeur G6 ; I3 bancs 14/14 PASS, oracles 32/32 PASS, 5 scénarios locaux bornés qualifiés, budget 171,1s / 4 200s | [SPEC-08](SPEC-08_Observability.md), [SPEC-09](SPEC-09_Performance.md), [SPEC-10 §§10–12](SPEC-10_Test_Certification.md#10-reconstruction), [Plan technique L6/I2](L6_I2_Technical_Implementation_Plan.md), [Acte de clôture L6/I2](L6_I2_Acceptance_and_Closure_Decision.md), [Plan technique L6/I3](L6_I3_Technical_Implementation_and_Verification_Plan.md), [Rapport I3](L6_I3_Implementation_and_Performance_Qualification_Report.md). Durées/sondes/échantillons, coûts Grand Pot/long historique à ratifier ; qualification à reconfirmer après convergence L7 |
| **L7 — OPEN, CERTIFICATION EN COURS** | Convergence V2 → Pocoma, nettoyage et certification fonctionnelle/technique du code unifié (§6) ; confirmation performance après changements affectant le coût | L2–L6 acceptés, inventaire de tous chemins et plan de retrait audité, exigences encore ouvertes recensées | Aucune voie productive historique, aucun namespace/suffixe v3 actif, schémas neufs et noms consolidés, suites applicables et SPEC-10 prouvées ; architecture globale + full requis pour restructuration ; audit indépendant du code unifié | [Canon §15](V3_Canon_and_Roadmap.md#15-livraison-finale-et-convergence), §6 présent. Retrait sans perdre oracle/preuve, périmètre migration de données conditionnel ; aucune certification étendue par simple renommage |
| **L8 — DESIGN PENDING** | Distribution finale facilement déployable : Compose monolithe prioritaire, distribuée si permise, PostgreSQL/autorisation/Prometheus/Grafana, Bruno final, guides et installation vierge (§7) | L7 accepté et code unifié certifié ; contrats images/configuration/topologies arrêtés, fournisseur d’autorisation qualifié | Installation neuve répétable, scénarios et balances conformes, healthchecks/migrations/imports automatiques, dashboards et logs, arrêt/reset ; parité topologies et provenance release ; tests runtime/packaging, gates globaux si changement structurel, audit final de distribution | §7 présent, [SPEC-06](SPEC-06_Supra_Contracts.md), [SPEC-08](SPEC-08_Observability.md), [SPEC-10](SPEC-10_Test_Certification.md). Packaging ne certifie pas à lui seul sécurité production/SLO ; adaptation de l’existant, nombre final de runtimes à cadrer |

## 4. Ordre et conditions de passage

Progression proposée : **L0 → L1 → L1-F → L1-F2 → L2 → L3 → L4 → L5 → L6 → L7 → L8**. Les contrats de leurs consommateurs peuvent être étudiés pour concevoir une fondation ; leur implémentation attend ses preuves et son acceptation. Une revue de contrat ne vaut pas livraison du consommateur.

L2 reçoit les garanties internes C corrigées par L1-F et les préconditions WRITE locales de L1-F2 et les étend au moteur effectif. Le plan L2 doit unifier v3_command_slot(request_id) avec ConsumptionSlot(consumptionId,consumableId), sans second moteur : [SPEC-05 §11](SPEC-05_Consumption_Discovery.md#11-passage-du-socle-command-l1-aux-slots-l2). Le pilote utilise la vraie mutation Pot L1 ; l’autorité Identity reste explicitement une fixture tant que L3 n’est pas livré. Un banc de transformation READ peut prouver les frontières L2 sans prétendre matérialiser le métier L4. L3 remplace cette fixture puis fait revérifier le contrat Command. L4 consomme les histoires réelles des deux domaines. L5 ferme les parcours HTTP, l’invitation et le consentement ; une preuve technique externe peut être étudiée pendant son cadrage, sans accepter un Link réel avant qualification. L6 mesure l’ensemble et prouve la maintenance. L7 certifie le code unifié après retrait ; toute évolution modifiant coût ou topologie invalide les mesures affectées, à refaire avant certification finale. L8 valide le livrable reproductible issu du même code.

Instrumentation et Bruno progressent avec les capacités concernées dès L2/L5 ; leur livraison finale et leur provisionnement appartiennent à L8. La certification fonctionnelle/technique s’accumule par lot, puis porte sur le code unifié L7. PERF-01 possède des objectifs conceptuellement définis dans SPEC-09 ; ses mesures et sa qualification V3 restent à produire à L6, puis à confirmer à L7/L8 si leurs changements affectent les mesures. Aucun PASS de performance V3 n’est acquis. V3-7 n’est clos qu’avec toutes les preuves applicables, les réserves explicites et la décision de certification ; aucune acceptation de lot ne le clôt implicitement.

À chaque passage prévu : dossier publié et vérification de provenance, INTERNAL PASS borné, AUDIT PENDING, revue indépendante, corrections bloquantes revérifiées, décision ACCEPTED. Pour L2, la décision humaine du 30 octobre clôt le lot avec réserves sans franchissement technique G3, selon la clôture officielle. Le lancement suivant exige son plan validé et son autorisation explicite. Aucun passage par silence ou autoévaluation.

## 5. Contrat d’exécution et gates des futurs lots

Chaque futur chat commence par ce plan, le canon, la politique normative de [vérification](../testing/Reactor_Verification_Policy.md), les SPEC applicables et les rapports amont. Recontrôler HEAD local/distant, divergence, worktrees et modifications ; ne pas utiliser une conversation comme spécification. Réactualiser I-01 sur les modules et contrats réellement touchés avant de déclarer les slices.

Au lancement de chaque lot, produire un plan de conception/vérification : objectifs et exclusions, contrats/invariants, inventaire de modules/runtimes/writers et décisions REUSE/ADAPT/REPLACE/RETIRE/DEFER, scénarios SPEC-10 et oracles indépendants, critères de certification, commandes Maven, modes DB, provenance et checkpoint d’audit. Les choix ouverts bloquant le lot sont tranchés avant ses écritures de code. Le rapport et le manifeste sont ouverts à ce lancement, complétés par les résultats frais puis publiés ; leurs artefacts attendus sont fixés avant tests.

Commandes canoniques actuelles, **prospectives pour L3–L8 et les qualifications L2 restant ouvertes**, depuis `app/` :

| Slice / gate | Commande |
|---|---|
| FOUNDATION (test infrastructure uniquement) | `./mvnw -pl infra-tx-spring -am test` |
| COMMAND | `./mvnw -pl runtime-command-consumption-worker -am test` |
| EVENT | `./mvnw -pl runtime-event-consumption-worker -am test` |
| PROJECTION | `./mvnw -pl runtime-task-consumption-worker -am test` |
| BINDING | `./mvnw -pl runtime-binding-consumption-worker -am test` |
| WEB | `./mvnw -pl runtime-web-api -am test` |
| LKV (tant que sa capacité historique existe) | `./mvnw -pl runtime-latest-known-version-consumption-worker -am test` |
| Architecture globale, quand requise | `./mvnw -pl architecture-tests -am test` |
| Full reactor, quand requis | `./mvnw test` |

Composer explicitement les ancres concernées avec `-pl a,b -am test`. Aucun `-amd` par défaut. Registration, Results et monolithe peuvent nécessiter des ancres complémentaires justifiées par le graphe réel ; ils ne sont pas de nouvelles slices canoniques décrétées ici. Réviser la politique et les ancres avec la rationalisation L7, avant usage des nouveaux noms.

Architecture globale requise si dépendances/modules/frontières/isolation changent ; full reactor aux clôtures majeures prévues, restructuration large ou intégration main. Aucun global/full automatique à chaque étape. Tout franchissement inattendu est expliqué et redéclaré avant poursuite. DB CURRENT pour les schémas concernés ; baseline seulement si TRUSTED démontrée. Pas de répétition séparée des migrations historiques par défaut ; tracer celles effectivement lancées transitivement. L’installation neuve L8 est une preuve distincte d’une migration de données V2.

Provenance : SHA testé propre, liste d’entrées et modes, outils/dépendances/images/configurations non secrètes, XML frais et reçus d’archives, résultats et skips, comparaison avant/après puis code testé→publication et tête distante. **Limite du vérificateur L1 existant :** sa liste d’exclusions est figée pour la campagne L1 ; elle n’autorise pas tous les futurs Markdown. Le conserver intact. La présente consolidation contrôle séparément son diff documentaire ; un échec d’exclusion ne doit pas être contourné ni présenté comme un nouvel A-02 PASS sur la feuille de route.

## 6. Convergence finale V2 → Pocoma — chantier L7 proposé

Principe normatif du [canon](V3_Canon_and_Roadmap.md#15-livraison-finale-et-convergence) : aucune coexistence de deux implémentations actives à la fin. Retirer progressivement les usages devenus inutiles pendant L2–L6, puis fermer exhaustivement à L7 ; aucun nettoyage exécuté par le présent mandat.

Le plan L7 devra couvrir : suppression du code V2 inutile, des runtimes/compositions obsolètes et des adaptations de compatibilité ; rationalisation Maven ; disparition des packages et suffixes `v3` du code actif ; retrait des flags, configurations, chemins et bootstrap transitoires ; consolidation des schémas et migrations de référence ; noms de modules/classes/interfaces exprimant Pocoma ; révision des tests, gardes d’architecture, scripts et outils ; documentation utilisateur/exploitant et README courant remplaçant les points d’entrée historiques.

Pour chaque retrait, recenser imports, POM de production/test, wiring Spring explicite et scans, ressources, migrations, Docker, scripts et consommateurs indirects. Associer chaque test à sa propriété : conserver, remplacer sous le contrat cible ou retirer avec justification si propriété disparue. Le retrait d’une ancienne implémentation ne justifie aucune baisse de garantie. Retirer aussi les exceptions ArchUnit V2 nominatives devenues inutiles ; aucune permission générale de contourner les ports.

**Acceptation de convergence :**

- Graphe Maven effectif sans modules historiques inutiles ni dépendance vers une ancienne implémentation ; architecture et full reactor applicables passent sur le code unifié.
- Inventaire exhaustif des points d’entrée productifs, imports/compositions/configurations/commandes de démarrage démontrant qu’aucun chemin de production ne sélectionne l’ancienne architecture, même par flag ou profil. Tests des compositions réellement distribuées et monolithiques, et analyse des classpaths/image contents.
- Aucun package/suffixe `v3` dans le code actif ; noms et documentation de démarrage convergent vers Pocoma. Les chemins `docs/v3` et preuves portant leurs noms historiques restent permis et nécessaires.
- Aucun bootstrap implicite des anciennes tables ou mécanismes Task/LKV/Binding autonome éliminés ; parcours et oracles applicables réexécutés, code réellement testé identique à celui publié.
- Rapport de retrait ancien→nouveau avec chemins, propriétés préservées et preuve d’absence de voies secondaires ; audit indépendant avant ACCEPTED.

**Données :** une installation vierge reçoit le schéma Pocoma final et ses migrations de référence. Le canon ne prescrit aucun système V2 déployé à migrer : ne pas inventer une migration de données V2 obligatoire. Si des données réellement nécessaires sont identifiées, arbitrer explicitement périmètre, mapping, conservation, restauration et preuve de migration dans un chantier distinct avant toute suppression pertinente. Une preuve de schéma neuf ou d’équivalence de baseline ne prouve pas la migration de données. Archives, rapports, SHAs et preuves brutes historiques restent auditables via chemins conservés ou index d’archives durablement référencés.

## 7. Distribution finale — exigence L8

**Livrable attendu :** distribution Docker Compose de référence reproductible depuis un environnement vierge, guide utilisateur et guide exploitant, collection Bruno importable/exécutable et observabilité provisionnée. Rien de ceci n’est construit dans cette clôture documentaire.

### Topologies et infrastructure

Privilégier pour démonstration/prise en main **un runtime Pocoma monolithe** regroupant les fonctions nécessaires, avec configuration minimale et démarrage simple. Prévoir aussi une topologie distribuée si l’architecture finale le permet : séparation selon responsabilités effectives, mêmes contrats et comportement métier. Aucun nombre de runtimes fixé avant cadrage et inventaire des compositions. Les frontières WRITE/READ restent physiques par transaction même dans un processus unique.

Compose doit contenir PostgreSQL, l'IdP (Keycloak comme premier banc), Prometheus, Grafana et les runtimes retenus. Depuis H2/H4 L5/I3, aucun serveur d'autorisation Link ni extension IdP supplémentaire n'est requis : qualifier JWT réels et POST explicite d'E2 sur la proposition immuable, sans affaiblir C-01 ni présumer le multi-issuer.

Prévoir images et versions identifiables, build ou récupération reproductible, dépendances ordonnées par disponibilité, réseaux et résolution des noms/issuer depuis hôte et conteneurs, volumes, healthchecks et readiness réels, migrations WRITE/READ automatiques sans course, données et configurations de démonstration initialisées de façon reproductible, ports et paramètres documentés, ressources et temps d’attente réalistes. Les images démontrées correspondent au SHA/code unifié certifié ; un `latest` flottant ou une image construite avec tests sautés ne constitue pas une preuve de release.

### Bruno et observabilité

Adapter la collection existante à l’API réellement livrée : environnements documentés monolithe/distribué, authentification fonctionnelle et sessions EUI distinctes, obtention de son propre bindingToken, IDs/requestIds/versions capturés et corrélation propagée. Parcours représentatifs : Registration, Command Pot/Shareholders/Expense/poids et balances exactes, historique/suppression, Link/Unlink et invitation selon contrats livrés ; succès, no-op, rejet et refus d’accès. Les résultats asynchrones sont vérifiés par polling borné avec timeout/backoff et diagnostics ; un seul passage CLI supposant une convergence immédiate ne suffit pas. Séparer `404` normal de polling et erreur finale ; aucun secret de production versionné.

Exposer les métriques, collecter avec Prometheus et provisionner datasource/dashboards Grafana sans import manuel. Indicateurs : admissions/commandes et issues, publications événementielles, slots/Consumptions et projections, débits, backlog/âge/retards, durées Discovery/acquisition/préparation/validation/commit, retries/CAS/erreurs/expirations/quarantaines, état des workers et dépendances. Les anciens indicateurs de tâches sont adaptés aux traitements réellement livrés ; aucune famille technique Task réintroduite pour un dashboard. Journaux structurés exploitables, corrélation requestId/eventId/Consumption et trace si utile, aucun token/secret, aucun identifiant à forte cardinalité en label Prometheus. Vérifier les séries et dashboards par succès, incident et reprise conformément à O-01, sans additionner les p95 des branches parallèles.

### Installation rapide et preuve sur environnement vierge

Le guide doit permettre de :

1. Récupérer dépôt ou distribution identifiée par version/SHA et vérifier les prérequis Docker/Compose et ressources.
2. Fournir une configuration minimale documentée, avec valeurs locales de démonstration clairement marquées.
3. Démarrer la composition monolithe de référence ; proposer la composition distribuée validée.
4. Attendre la disponibilité effective des services avec délai et diagnostic explicites.
5. Initialiser automatiquement schémas, realm/configuration d’autorisation et données de démonstration utiles, sans SQL manuel complexe.
6. S’authentifier avec la procédure documentée puis obtenir son propre binding lorsqu’il existe.
7. Importer/exécuter le scénario Bruno de référence et gérer ses corrélations.
8. Vérifier résultats asynchrones, versions et balances exactes attendues.
9. Ouvrir métriques/dashboards provisionnés et retrouver les opérations dans les logs.
10. Arrêter, redémarrer avec données persistantes, puis remettre à zéro volontairement selon une procédure explicite et documentée.

Objectif : installation simple, sans intervention manuelle complexe ; aucune promesse de démarrage instantané quelle que soit la machine. Démonstration locale et production sécurisée sont documentées séparément : secrets externes, TLS/issuer, accès réseau, comptes/rôles, sauvegarde/restauration, quotas et exposition monitoring sont à définir pour la production, sans présenter les comptes ou mots de passe locaux comme configuration sécurisée.

**Acceptation L8 :** répéter le parcours depuis volumes/images/configuration initialement absents dans un environnement déclaré ; garder commandes, versions/images, logs de bootstrap/health, résultats Bruno et balances, captures/export dashboards, arrêt/reprise/reset. Prouver la parité fonctionnelle des deux topologies annoncées ; si la distribuée n’est pas livrée, documenter l’arbitrage et la limitation avant acceptation. Tester installation vierge séparément de toute migration V2 éventuelle. Audit indépendant de la distribution, provenance et concordance avec le code certifié ; aucune certification performance par simple démarrage de Compose.

## 8. Inventaire vérifié au départ et réemploi

Inventaire statique à la référence d’ouverture ; disponibilité d’un fichier ≠ fonctionnement V3 démontré. Les modifications non publiées des autres worktrees ne sont pas considérées comme livrables.

| Existant localisé | Constat / suite du programme |
|---|---|
| [POM racine](../../app/pom.xml), [graphe L0](L0_Maven_Graph.json) | 60 modules enfants : 6 domaines, 4 contrats, 3 ports, 17 engines, 10 supras, 2 orchestrateurs, 1 projecteur, 5 infrastructures, 1 composition, 10 runtimes et architecture-tests ; pas de nouveau module requis par principe |
| [runtime-monolith](../../app/runtime-monolith/), [composition](../../app/composition-runtime-spring/) | Monolithe déjà présent ; importe Web, Registration/Result, Binding, Command/Result, Event et ProjectionTask historiques. Une factory Pot L1 existe ; aucun parcours V3 externe complet certifié |
| Neuf runtimes séparés sous [app](../../app/) | `runtime-web-api`, `runtime-command-consumption-worker`, `runtime-command-result-consumption-worker`, `runtime-event-consumption-worker`, `runtime-task-consumption-worker`, `runtime-binding-consumption-worker`, `runtime-latest-known-version-consumption-worker`, `runtime-registration-consumption-worker`, `runtime-registration-result-consumption-worker` ; rationaliser selon responsabilités finales, pas neuf futurs services imposés |
| Packages de production contenant `v3` | 43 sources : domain-pot 27, engine-write-pot 9 (ports inclus), infra-persistence-primary-jpa 6, composition-runtime-spring 1 ; [inventaire L1-C](L1_C_Transaction_Contract.md) et [fichiers D/E](../testing/evidence/L1-DE/Changed-files-final.json). Namespace transitoire à retirer L7, pas maintenant |
| Migrations | PRIMARY V1–V31 ; READ V1–V14 ; V3 `db/v3-pot/migration` V1 et `db/v3-pot-execution/migration` V2 ; fixture L0 séparée. [Contrat B](L1_B_Persistence_Contract.md). Aucune baseline V2 TRUSTED ; schémas V3 internes ≠ distribution finale |
| [Compose fonctionnel](../../docker-compose.functional.yml), [Dockerfile](../../app/Dockerfile), [PostgreSQL local](../../app/docker-compose.postgres.yml) | Stack historique monolithe/PostgreSQL/Keycloak avec init et healthchecks déjà présente ; version PG différente du banc L1. À adapter/qualifier, pas déclarée distribution V3 |
| [Compose distribué](../../docker-compose.distributed.yml), [docker](../../docker/) | API, workers Event/Task/Command/Result/LKV, PostgreSQL, Prometheus, Grafana ; cette composition ne couvre pas à elle seule tout le cycle Identity. Inventaire final des services requis avant packaging |
| [Realm Keycloak](../../docker/keycloak/pocoma-realm.json), [init PG](../../docker/postgres/functional-init.sql) | Acquis de démonstration locale ; aucun PASS de consentement/RAR déduit de leur présence |
| [Bruno](../../scripts/bruno/), [guide](../../scripts/bruno/README.md) | Collection et environnement Local : Pot E2E, commandes/queries/expenses/balances, Registration→Command, cleanup. Références bindingId/routes historiques et polling manuel à adapter aux contrats V3 ; authentification automatisée finale encore à vérifier |
| [Prometheus](../../docker/prometheus/prometheus.distributed.yml), [Grafana](../../docker/grafana/), [observabilité](../../app/contracts-observability/) | Actuator/Micrometer et corrélation déjà présents ; datasource/dashboard provisionnés. Métriques actuelles historiques, conformité O-01 future à vérifier |
| [k6](../../scripts/k6/), [collecteurs](../../scripts/testing/) | Smoke/stress/backpressure et collecte XML/provenance L0/L1 présents ; adapter les scénarios, aucun PASS de performance V3 existant |

Plans examinés : [Discovery Step Plan](../steps/current/DISCOVERY/Step_Plan.md), [Next Plan](../steps/current/DISCOVERY/Next_Plan.md), [A2 minimal](../plans/2026-10-22-a2-minimal-consumption-discovery.md), [A2-1](../plans/2026-10-22-a2-1-consumption-contract-implementation-plan.md), [migration modulaire historique](../steps/completed/ARCHITECTURE/Modularity_Migration_Plan.md), [clôture POST-WP6](../steps/completed/ARCHITECTURE/Modularity_Post_WP6_Closure_Report.md), inventaires L0/L1 et audits conceptuels V3. Leurs stratégies de compatibilité/cutover sont supplantées pour V3 par le canon ; leurs garanties et preuves restent historiques. Leurs anciens « NEXT » ne pilotent pas le programme V3.

## 9. Documents disponibles, manquants et reprise du prochain lot

| Lot | Documents disponibles | À produire lors du lancement / de la livraison |
|---|---|---|
| L0 | Plan, inventaire, graphe, rapport, manifeste et archives liés §3 | Aucun nouveau plan ; compléments transversaux I-01 selon lots futurs |
| L1 | Plan A…E, contrats B/C, rapports A/B, C, D, E, manifeste, index/archives/matrice SPEC-10 ; [clôture](L1_Documentary_Closure.md) | Rapport original détaillé d’audit final à rattacher s’il devient disponible ; acceptation transmise par mandat distinguée des preuves techniques |
| L1-F | [F0](L1_F_Contradictory_Inventory.md), [rapport](L1_F_Closure_Report.md), [manifeste](L1_F_Proof_Manifest.json), archives/mesures/index | Corrections internes CLOSED/PASS ; cycle Invitation réel et READ explicitement aux lots futurs |
| L1-F2 | [Inventaire](L1_F2_Contradictory_Inventory.md), [matrice](L1_F2_Invariant_Matrix.md), [rapport](L1_F2_Closure_Report.md), [manifeste](L1_F2_Proof_Manifest.json), preuves PG/performance | CLOSED/PASS interne ; revue indépendante avant entrée L2 |
| L2 | [Clôture documentaire officielle](L2_Documentary_Closure.md), [plan](L2_Design_and_Verification_Plan.md), [dossier I6 et preuves](L2_I6_I7_Consolidation.md), rapports et manifestes historiques I1–I5 | ACCEPTED WITH EXPLICIT RESERVATIONS ; aucun complément requis pour cette acceptation de programme ; réserves ouvertes et qualification technique restante conservées pour les jalons appropriés |
| L3 | [Plan Identity](L3_Identity_Inventory_and_Implementation_Plan.md), contrats I1, autorité I2, [livraison I3/R-ID](L3_I3_Execution_Report.md), [résolution et preuves](L3_I3_Findings_Resolution_Report.md), [ré-audit Gemini](L3_I3_Independent_Reaudit_Report.md), [décision I3](L3_I3_Acceptance_Decision.md), [livraison I4](L3_I4_Execution_Report.md), [décision I4](L3_I4_Acceptance_Decision.md), [rapport I5](L3_Identity_Execution_Report.md), [runbook](L3_Identity_Runtime_Runbook.md), [manifeste I5](L3_Identity_Proof_Manifest.json), [clôture L3](L3_Identity_Closure.md) | CLOSED WITH EXPLICIT RESERVATIONS. O20/O25 SATISFIED WITH EXPLICIT RESERVATIONS ; O21 SATISFIED WITH RESERVATIONS ; strict INCONCLUSIVE O20/O21 conservé ; L4 autorisé au cadrage après publication vérifiée |
| L4 | [Acte de clôture](L4_Read_Closure_Decision.md), [plan global](L4_Read_Inventory_and_Implementation_Plan.md), [plan I8](L4_I8_Implementation_and_Qualification_Plan.md), [rapport I8](L4_I8_Execution_Report.md), [manifeste figé](L4_I8_Proof_Manifest.json), [décision I1](L4_I1_Acceptance_Decision.md), [décision I2](L4_I2_Execution_Report.md#clôture-définitive-du-3-novembre-2026), [livraison I3–I7](L4_I3_I7_Execution_Report.md), [recovery](L4_Recovery_Audit_Resolution.md) | CLOSED WITH EXPLICIT RESERVATIONS — COMPOSITE INTERNAL QUALIFICATION ACCEPTED ; aucun full natif PASS ni certificat SPEC-10 global ; réserves et reports L5/L6/L7 conservés |
| L5 | [Plan global](L5_Supra_Entry_Audit_and_Implementation_Plan.md), [clôtures I1](L5_I1_Acceptance_and_Closure_Decision.md), [I2](L5_I2_Acceptance_and_Closure_Decision.md), [I3](L5_I3_Acceptance_and_Closure_Decision.md), [I4](L5_I4_Acceptance_and_Closure_Decision.md), [plan Q5](L5_Q5_Final_Qualification_Plan.md), [rapport certification Q5-B6](L5_Q5B6_Final_Certification_Execution_Report.md), [preuves Q5-B6](../testing/evidence/L5-Q5B6/README.md), [audit Q5-C](L5_Q5C_Quality_Performance_and_Restructuring_Audit.md), [acte de clôture formelle Q5-D](L5_Q5D_Formal_Closure_Decision.md) | CLOSED WITH EXPLICIT RESERVATIONS. Certification globale Q5-B6 (2 344 tests PASS, 170 Arch PASS, B5 70/70 PASS). Audit Q5-C accepté. Réserves conservées et attribuées. Restructurations différées après L6. L6 éligible au cadrage. |
| L6 | [Plan global](L6_Design_and_Verification_Plan.md), [plan I1](L6_I1_Technical_Implementation_Plan.md), [rapport I1](L6_I1_Implementation_and_Qualification_Report.md), [rapport post-audit I1](L6_I1_Post_Audit_Corrections_and_Requalification_Report.md), [acte de clôture I1](L6_I1_Acceptance_and_Closure_Decision.md), [plan technique I2 consolidé](L6_I2_Technical_Implementation_Plan.md), [rapport de cadrage I2 consolidé](L6_I2_Scoping_and_Design_Report.md), [rapport d'implémentation I2](L6_I2_Implementation_and_Verification_Report.md), [rapport post-audit I2](L6_I2_Post_Audit_Corrections_and_Requalification_Report.md), [rapport correctifs finaux de sûreté I2](L6_I2_Final_Safety_Corrections_and_Requalification_Report.md), [acte de clôture I2](L6_I2_Acceptance_and_Closure_Decision.md), [cadrage I3](L6_I3_Scoping_and_Design_Report.md), [plan technique I3](L6_I3_Technical_Implementation_and_Verification_Plan.md), [rapport d'implémentation et qualification locale I3](L6_I3_Implementation_and_Performance_Qualification_Report.md) | OPEN — I1 & I2 CLOSED WITH EXPLICIT RESERVATIONS ([acte I1](L6_I1_Acceptance_and_Closure_Decision.md), [acte I2](L6_I2_Acceptance_and_Closure_Decision.md)) ; I3 IMPLEMENTED WITH BOUNDED LOCAL QUALIFICATION PASS ([rapport I3](L6_I3_Implementation_and_Performance_Qualification_Report.md) : 14/14 tests bancs PASS, 32/32 oracles PASS, 5 scénarios locaux PASS, budget 171,1s / 4 200s). Campagnes longues SPEC-09 différées pour jalon G6/L8. G6 PENDING. |
| L7 | [Cible normative](L7_Target_Modular_Architecture_and_Migration_Plan.md), [plan d’exécution](L7_Modular_Refactoring_Execution_Plan.md), [rapport et preuves](L7_Modular_Refactoring_Implementation_and_Qualification_Report.md) | Réacteur V3 à 42 modules et qualification du code unifié |
| Certification finale L7 | [Plan L7.1](L7_I1_Certification_Coverage_and_Execution_Plan.md), [matrice](L7_I1_Certification_Coverage_Matrix.csv), [réserves](L7_I1_Residual_Reservations_Register.md) | Qualification du socle restructuré et préparation des incréments L7.2–L7.5 |
| L7.2–L7.4 | [Fonctionnel et sécurité](L7_I2_Functional_Security_and_Bruno_Certification_Report.md), [sûreté et READ](L7_I3_Operational_Safety_and_READ_Certification_Report.md), [protocole SPEC-09](L7_I4_SPEC09_Performance_Certification_Execution_Plan.md) | L7.2 certifié avec réserves. L7.3 NOT_CERTIFIED ; exécution technique L7.4 autorisée et E1/E3 qualifiés localement, campagnes contractuelles NOT_RUN faute de topologie A/B. Une candidate E02-J sans promotion automatique. L7.5 BLOCKED ; L7 OPEN. [Bilan L7.4](L7_I4_SPEC09_Technical_Qualification_Report.md). |
| L8 | Existant Docker/Bruno/observabilité §8, contrat §7 | Plan packaging/installation, guides utilisateur/exploitant, manifeste de distribution, preuves installation/parité et audit |

La conception L2 et A01–A12/R1–R10 restent validés. L2 conserve son acceptation
avec réserves. **L3 est CLOSED WITH EXPLICIT RESERVATIONS**, selon l’[acte
unique](L3_Identity_Closure.md). I1 L4 est clos selon l’[acte humain](L4_I1_Acceptance_Decision.md) ;
I2 est CLOSED — LOCAL BOUNDED PASS selon la décision du 3 novembre 2026. Les invariants,
scénarios et gates normatifs demeurent applicables.

**Travaux suivants :** I1, I2, I3 et I4 sont clos avec réserves explicites (statut LOCAL BOUNDED PASS). Le lot L5 reste OPEN dans l'attente de la phase de qualification globale Q5 / G5 (tests Architecture non filtrés, full reactor natif unique G5, Bruno B5 de bout en bout et certification globale SPEC-10). Préserver l'ensemble des réserves R-I1…R-I4 et les reports humains associés. Instruire également
les sessions, la rétention d’idempotence et les bornes de protection L5 ; ratifier
le protocole Grand Pot/historiques/sondes L6 ; préparer retrait/noms/schémas neufs
L7 et éventuel besoin de données V2 ; définir topologies/images/bootstrap et
exploitation L8. Les transferts L4/L5/L6–L8, réserves L2/L3 et critères G3 restent
dus. Révocation anticipée des invitations hors périmètre jusqu’à arbitrage du canon.

### Orientation à instruire au cadrage L4 — Qualification réutilisable de L2 Fondation

Cette orientation historique est déclinée par le [plan global L4](L4_Read_Inventory_and_Implementation_Plan.md).
Depuis la clôture I1, sa prochaine application relève du cadrage I2 : les preuves
affectées de préparation/recovery/owners, dont le poisoning tardif I1, doivent
faire l’objet d’une analyse et d’une requalification ciblées.

Cette orientation de cadrage distingue trois catégories :

| Catégorie | Évolution et qualification à instruire |
|---|---|
| **L1 — Métier Pocoma : évolutif** | Les composants métier L1 restent susceptibles d’évoluer et sont testés selon leurs changements et leurs contrats. |
| **L2 — Fondation technique générique : stabilisable** | Les mécanismes génériques Consumption/Discovery doivent pouvoir être qualifiés comme composants réutilisables, sans rejouer systématiquement leurs tests physiques lourds lorsque leurs hypothèses restent inchangées. |
| **L2 — Branchement Pocoma : évolutif** | Les adaptations, assemblages et intégrations métier de L2 restent soumis aux tests de contrat et d’intégration des lots concernés. |

C’est un **sujet de cadrage, aucune certification déjà obtenue**. Avant toute
évolution des gates, il faudra identifier exactement la frontière
Fondation/Branchement, établir l’inventaire des dépendances et tests correspondants,
définir les conditions de validité et de réutilisation des preuves et vérifier
leur compatibilité avec la politique Reactor. Toute extraction physique prématurée
des modules doit être évitée. **Aucune réduction des gates existantes n’est autorisée
par la seule clôture L3.** Aucun module ou contrat n’est modifié dans cette mission.

**Reprise autoportante historique de la publication I2 :** ouvrir ce plan → lire la [clôture L2](L2_Documentary_Closure.md), le canon, les SPEC et la politique → vérifier Git et les preuves référencées → lire l’[arbitrage I2 / GO I3](L3_I2_Acceptance_Decision_2026-10-30.md) et le [plan L3](L3_Identity_Inventory_and_Implementation_Plan.md) → déclarer le scope I3 avant toute implémentation, puis suivre ses preuves et checkpoints. I1/I2 restent acceptés avec réserves, leurs gates canoniques en échec ; aucune dispense des gates I3 ou ultérieures. La présente intervention s'arrête à la publication documentaire et ne commence pas I3.


## Historique L2 — décisions et contributions datées

Les sections suivantes conservent les états observés à leur date, y compris les formulations « état courant », audits attendus et L2 non accepté. Pour l'état officiel du programme depuis le 30 octobre 2026, lire la [clôture L2](L2_Documentary_Closure.md) ; aucun verdict technique historique ci-dessous n'est requalifié.

### Décisions L2 R1–R10 consolidées (2026-10-26)

[Contrat normatif consolidé R1–R10](L2_R1_R10_Normative_Consolidation.md). Les dix constats sont CLOSED conceptuellement, non certifiés ; GO humain et configuration pilote reçus, G0 franchi par décision humaine explicite consignée dans le [plan I1 amendé](L2_I1_G0_Readiness_and_Implementation_Plan.md). R3 amende A03 : quatre tables techniques par autorité concernée. Aucun code, DDL ni test de qualification L2 n'a été exécuté par cette consolidation.


### Clôture I1 et préparation I2 — décision courante du 26 octobre 2026

**I1 — CLOSED / LOCAL BOUNDED PASS**, livraison `a2e6814f6ebf8d2ee87d88d16ad2dfa4fe07f512`, acceptée humainement après revue contradictoire. [Statut, provenance et limites](L2_I1_G0_Readiness_and_Implementation_Plan.md#10-clôture-définitive-i1-et-préparation-i2--26-octobre-2026) : 33 méthodes I1 exécutées, preuves PostgreSQL réel et COMMAND/READ/MONOLITH/architecture archivées, régressions L1 préservées, skip exploratoire V2 conservé. Recontrôle d'archives/XML/provenance seulement ; aucune campagne relancée, full reactor non requis à I1.

**I2 — CLOSED / LOCAL BOUNDED PASS.** [Code, preuves et limites](L2_I2_Execution_Report.md) ; GO humain reçu après audit du plan simplifié, quatre gates canoniques réussis au SHA final. Cette clôture historique I2 imposait l’arrêt avant I3 ; le mandat I3 et sa clôture du 27 octobre ci-dessous gouvernent désormais la suite. Le paragraphe de préparation ci-dessous est historique.

**Préparation historique I2.** Le [plan dédié](L2_I2_Implementation_Plan.md) fixe extraction du participant Command, owner physique commun, R1/R9, DDL/locks, fichiers/signatures, preuves et slices. Recommandation technique GO sur ce périmètre, sous audit du plan et instruction humaine avant réalisation. Acquisition/leases/cession/retry/quarantaine restent I3, Discovery I4 et raccord actif I5. G1/G2/G3 restent futurs ; **L2 non ACCEPTED globalement**. La préparation documentaire ne vaut aucun PASS I2.


### Préparation I3 — mandat documentaire du 27 octobre 2026

[Plan technique I3 proposé pour audit](L2_I3_Implementation_Plan.md), préparé depuis la baseline I2 `03f0725c690bcbd9748d3ea59b62136e1a911e05` : ownership, claims/renewals, capacité, classification/cession/retry, quarantaine et réactivation administrative ; SQL, fichiers, preuves PostgreSQL et gates prospectifs détaillés. **Préparation et publication documentaire seules autorisées ; aucun code, test I3 ou migration modifié, aucune campagne applicative exécutée.** Aucun nouvel arbitrage normatif bloquant identifié ; choix techniques soumis à l’audit indépendant du plan. Attendre le GO humain distinct avant implémentation I3 ; I4/I5 et G1/G2/G3 restent futurs, L2 non ACCEPTED globalement.


### I3 — mandat d’implémentation du 27 octobre 2026

Le [plan I3](L2_I3_Implementation_Plan.md), commit approuvé `bb33b56dc86a8a1d31c577f983d7ea1a07bc8128`, a reçu l’avis indépendant **PASS WITH MINOR AMENDMENTS** puis le **GO humain explicite** d’implémentation/publication. Les interdictions du mandat documentaire précédent sont historiques. R-I3-01/02/03 sont reprises dans le plan et le [rapport d’exécution](L2_I3_Execution_Report.md) ; [manifeste figé](L2_I3_Proof_Manifest.json). État courant : **I3 CLOSED / LOCAL BOUNDED PASS — AUDIT PENDING**, code vérifié `1d7cf51c`, quatre gates sans filtre PASS ; les [preuves](../testing/evidence/L2-I3/README.md) conservent diagnostics et limites. Aucun GO I4/I5, aucune activation production, G1/G2/G3 non franchis, L2 non ACCEPTED. Après I3 : arrêt et audit indépendant code+preuves puis nouvelle décision humaine.

## Contribution I4 — Discovery, 28 octobre 2026

GO humain sur le [plan I4 approuvé](L2_I4_Implementation_Plan.md), référence `489259dbad757e3256572f8ce2041a187adeb38d`. Discovery indépendante implémentée : fenêtre globale dense et préfixe couvert avec K/slots atomiques ; tour UUID volatil et stratégie B PENDING protégée / existence de tout slot / INSERT strict. Owners physiques I2/I3 et finaliseur Command conservés, profil SQL combiné explicite et factories inactives. [Rapport d’exécution](L2_I4_Execution_Report.md), [manifeste de preuves](L2_I4_Proof_Manifest.json).

**État courant : I4 LOCAL BOUNDED PASS sur le code figé `0b08d3bf0bfce1334260cfd5249b13c3040a67bb` — audit indépendant encore attendu.** Les 49 méthodes PostgreSQL et leurs sous-variantes obligatoires ont passé sans skip I4 ; les quatre gates canoniques sans filtre passent au même SHA, avec archives et empreintes concordantes. Contribution bornée aux primitives disponibles Command/POT/COMMAND_COMPLETION ; autres producteurs et effets métier absents restent explicitement techniques dans les preuves. Cette contribution ne promeut aucune des 380 allocations ni les 56 reports normatifs.

L2 global demeure **INCONCLUSIVE**, G1/G2/G3 non franchis. I5, les Workers, HTTP et les parcours métier L3/L4/L5 restent hors GO. Audit indépendant du code et des preuves encore attendu ; le rapport d’implémentation n’en tient pas lieu.


## Contribution I5 — Workers sous GO distinct, 28 octobre 2026

Le GO humain explicite d’implémentation du [plan I5 amendé](L2_I5_Implementation_Plan.md) remplace, pour I5 seulement, l’interdiction historique du mandat documentaire. Deux boucles, navigation R10, capacité unique, renewals indépendants, quatre pools, drain et activation DEV fail-closed sont implémentés et qualifiés localement au gel `9a315e16`, avec cinq gates canoniques PASS ; audit indépendant encore requis. [Rapport d’exécution I5](L2_I5_Execution_Report.md), [manifeste additionnel](L2_I5_Proof_Manifest.json), [runtime de développement](L2_I5_Worker_Development_Runbook.md).

Les preuves I5 sont additionnelles aux allocations existantes : aucune ligne de couverture, obligation, oracle, sous-variante ou report historique n’est supprimé ni promu automatiquement. Les cinq réserves R-I5-01…05 ont des variantes obligatoires H1…H4/D1…D6/P1…P7 et des oracles de lenteur/activation ; R-AUD-I3-03 nécessite les preuves A/B/C distinctes WRITE/READ. Le statut effectif des campagnes et les empreintes du gel se lisent dans le manifeste/rapport, pas dans ce paragraphe.

Command est réel, Identity reste fixture de développement et les deux probes READ sont techniques ; elles ne certifient aucune projection L4. L3–L8, G1/G2/G3, les 56 reports normatifs et l’acceptation globale L2 restent gouvernés par leurs décisions propres. **L2 global INCONCLUSIVE ; I5 CLOSED BY HUMAN ACCEPTANCE WITH AVAILABILITY RESERVE par la décision du 29 octobre ci-dessus.** Les conclusions techniques de l’auteur et les audits historiques restent distincts de cette acceptation humaine ; AUD-I5-01 et R-AUD-I3-03 restent non certifiés techniquement. Poursuite I6/I7 autorisée, sans homologation production.


**Reprise historique après acceptation I3 :** lire la [décision humaine I3](L3_I3_Acceptance_Decision.md),
la résolution et le ré-audit liés, puis contrôler Git et les preuves publiées.
**Prochaine étape : cadrage et préparation I4**, sans implémentation dans cette
clôture. Préserver les garanties I1–I3, les frontières transactionnelles L2 et
les réserves R1–R4. Aucun PASS canonique, certificat global L3, R-ID-POT ou GO
d'implémentation I4/I5 ne découle de cette publication documentaire.


**Reprise historique I5 après I4 — READY FOR FRAMING ONLY :** lire la
[décision I4 et ses conditions de reprise autonome](L3_I4_Acceptance_Decision.md#11-conditions-de-reprise-autonome-dans-un-nouveau-chat-i5),
le canon, le plan L3 et les décisions I1–I4 ; vérifier Git et le dossier publié.
Inventorier les obligations I5 restantes et la complétude des critères L3,
distinguer preuves bornées et gates FAIL, examiner `i5_r05`/`i5_r12` pour une
éventuelle clôture, puis déterminer les vérifications proportionnées selon la
politique actuelle. Le cadrage doit établir ce qui est bloquant, sans présumer
correction ou dispense des défauts historiques. Préparer un plan puis le soumettre
à audit avant tout GO d'implémentation. **I5 NEXT — cadrage/préparation seuls
autorisés ; aucun développement I5. L3 OPEN ; aucune certification globale.**

**Reprise historique après L3, supplantée par la transition I1 → I2 en tête :** vérifier la publication de l’[acte L3](L3_Identity_Closure.md),
Git et les [preuves I5](../testing/evidence/L3-I5/README.md), puis ouvrir le cadrage
L4 en reprenant l’orientation Fondation/Branchement ci-dessus et toutes les réserves.
L3 CLOSED WITH EXPLICIT RESERVATIONS ; aucune implémentation L4 ou réduction de
gates autorisée par cette publication.
