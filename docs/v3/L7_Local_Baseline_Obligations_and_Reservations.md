# L7-LB — Obligations, faisabilité locale et réserves

13 novembre 2026 · analyse seulement · [cadrage](L7_Local_Baseline_Scoping_Report.md).
La classification ci-dessous ne modifie aucun verdict historique.

## 1. Deux axes indépendants

| Faisabilité probatoire | Sens |
|---|---|
| LOCALLY_CERTIFIABLE | Le stimulus et son oracle contractuel sont réalisables sur le socle local prévu, après les compléments indiqués ; ne signifie pas PASS |
| LOCALLY_TESTABLE_NON_CONTRACTUAL | Un essai local apporte une caractérisation utile mais ses conditions ne prouvent pas l'obligation contractuelle complète |
| REQUIRES_EXTERNAL_INFRASTRUCTURE | Les conditions de l'obligation entière excèdent le Mac/Compose prévu ; autre infrastructure requise, pas nécessairement un cloud |
| NOT_YET_COVERED | Pas de preuve ou d'outil adéquat identifié pour fermer l'obligation, ou inventaire sémantique encore nécessaire ; ce manque n'est pas une impossibilité locale |

Verdict séparé de preuve : **PASS, FAIL, PARTIAL, NOT_RUN**. PARTIAL désigne une
contribution historique insuffisante pour l'obligation entière ; NOT_RUN désigne
l'absence de preuve complète exécutée dans les conditions visées. Les statuts
bruts `CURRENT_FULL`, `HISTORICAL_PARTIAL`, `MISSING`, `UNKNOWN`, `INCONCLUSIVE`,
retrait humain, FAIL et SKIP sont conservés dans leur source, sans normalisation
destructrice. Tous les nouveaux essais de la future baseline sont **NOT_RUN**.

Les verdicts PASS de méthodes ou de banc E1 ne deviennent pas PASS d'une
obligation entière. Les lignes retirées par [décision Link](L7_I2_Link_Retirement.md)
restent RETIRED_BY_HUMAN_DECISION, hors obligations applicables ; ne pas leur
attribuer artificiellement une catégorie de faisabilité ou un PASS.

## 2. Réconciliation du corpus et couverture SPEC-10

La [matrice L7.1 courante](L7_I1_Certification_Coverage_Matrix.csv) contient
**522 IDs** : 432 affectés à L7.3, 52 à L7.2, 20 à L7.4, 18 à L7.5.
Statuts courants sur 522 : 53 CURRENT_FULL, 399 CURRENT_PARTIAL,
49 HISTORICAL_PARTIAL, 5 HISTORICAL_REUSABLE, 14 MISSING, 2 retraits.
Sur les 432 L7.3 : **48 CURRENT_FULL, 353 CURRENT_PARTIAL,
30 HISTORICAL_PARTIAL, un retrait**. Le dernier sous-ensemble rapproché
contient 31 IDs : 16 CURRENT_FULL et 15 CURRENT_PARTIAL ; les
**338 autres CURRENT_PARTIAL L7.3** restent à instruire. Aucun dénominateur
432/522 ne sert à annoncer un pourcentage de certification global.

On conserve cette matrice unique, plutôt qu'une copie de 522 lignes qui
divergerait. I1 doit en produire une allocation par ID : obligation conservée,
preuves au SHA exact, hypothèses anciennes→cible, pertinence locale, tâche,
responsable et sévérité A/B/C. L'inventaire incomplet vaut NOT_YET_COVERED et
**blocage A avant acceptation**, jamais transfert C par défaut. Les familles
ci-dessous orientent cette allocation ; elles ne clôturent aucun ID.

| Contrat et corpus à allouer | Acquis / preuve réutilisable | Faisabilité proposée et verdict historique consolidé | Reste à faire avant baseline ; différé autorisé |
|---|---|---|---|
| DOM-01, DOM-02, DOM-03 ; SPEC-10 §3 ; PRJ-01/02/03 §7 | L1-F/F2, L4, L5 Q5 ; tests Pot/history/fractions et vrais participants READ référencés par matrice | LOCALLY_CERTIFIABLE ; PARTIAL au niveau variantes complètes | Versions exactes, suppression, sous-versions hors ordre, balances rationnelles, reprises ; A pour invariant. Coût grand volume externe traité séparément |
| ID-01, SEC-01/02 ; §§4/8 | L3 clôture, L7.2, READ CurrentBinding, JWT réel et contrôle de privilèges | LOCALLY_CERTIFIABLE ; PARTIAL | Registration/Unlink, identité historique, ancien binding, refus tiers, cohérence éventuelle annoncée ; A. Link retiré n'est pas une lacune à recréer |
| CON-01…06 ; SPEC-10 §§5/6 et amendements R1–R10 | 386 lignes Consumption L2 ; receipts L7.2, completion/final et contribution E3 | NOT_YET_COVERED pour les variantes non rapprochées ; 15 sujets précis LOCALLY_CERTIFIABLE ci-dessous ; PARTIAL | Audit sémantique des 338 autres partielles et 30 historiques ; composer les slices réelles ; A pour atomicité/fencing/non-purge/perte. Aucun besoin cloud déduit d'un oracle manquant |
| ADM-01, S-01/02/03 ; A10-A…K, A11-A…N ; §9 | B1 fixture séparée, B2 public, B4 invitation, installation L7.2 | LOCALLY_CERTIFIABLE pour comportements ; PARTIAL | Cas par endpoint, idempotence, pagination, confidentialité, erreurs et DeletePot. A11-L/M sous SLO A relèvent de la matrice performance |
| INV-01 / INV-A…J ; §14 | L5/I4 et vrais PG/concurrence L7.3 final | LOCALLY_CERTIFIABLE ; PARTIAL global | Invitation expirée/falsifiée, place déjà prise, concurrence, détachement, histoire et secret non divulgué ; A pour parcours annoncé |
| REC-01 C-02 ; §10 | L6/I2 fermé avec 7 réserves ; CLI v3-rebuild-read et ReadinessOracle | LOCALLY_CERTIFIABLE pour reset READ coordonné/rejeu local ; PARTIAL sur cible cinq JVM vierge | Reprise, exclusion, bornes/checkpoints, conservation WRITE et refus faux READY ; A. Déploiement distribué/production C distinct |
| OPS-01 / O01-A…N ; §11 ; O-01 / SPEC-08 | Micrometer, Prometheus, cinq rôles, dashboards L6/I1, reprise E3 | LOCALLY_CERTIFIABLE pour diagnostic/reprise locale ; PARTIAL | Signaux par rôle, ressources/âge source et slot, corrélation reçus, incidents collecteur ; A si diagnostic/readiness faux, B pour ergonomie sans garantie retirée |
| PERF-01 F01-A…J et AUD-V3-013 ; §12/24 | Banc E1/E3 et scripts SPEC-09, L6/R audits et mesures bornées | Local diagnostics LOCALLY_TESTABLE_NON_CONTRACTUAL ; preuve A/B REQUIRES_EXTERNAL_INFRASTRUCTURE ; NOT_RUN contractuel | Voir 31 lignes de la matrice locale. Les seuils de latence et profils normatifs restent dus à L8, catégorie C explicite |
| ARC-01…13, CERT-FULL/ARCH/BRUNO/SPEC10/AUDIT/TRACE ; §13 et politique | Réacteur unifié, architecture et full L7.2, artefacts/provenance existants | LOCALLY_CERTIFIABLE pour gates locales ; NOT_YET_COVERED pour allégation SPEC-10 globale tant que les variantes ne sont pas allouées ; PARTIAL | Gate fraîche de stabilisation, audit, contrats publics et manifeste ; A. CERT-SPEC10 ne devient pas PASS avec un sous-périmètre local accepté |

## 3. Traitement individuel des 15 obligations L7.3

Sources : [rapport final](L7_I3_Final_Certification_Report.md),
[réconciliation machine](../testing/evidence/L7-I3-final/reconciliation.json),
[contribution L7.4](L7_I4_Contribution_to_L7_I3_Report.md).
Toutes ont le statut officiel **CURRENT_PARTIAL**, verdict d'obligation
**PARTIAL**, catégorie de réserve **A bloquante** et faisabilité proposée
**LOCALLY_CERTIFIABLE**. Aucun scénario n'exige intrinsèquement deux machines
4/8, un cloud ou une latence réseau externe. « Certifiable localement » reste
conditionnel à l'oracle exact ; de nouveaux tests/harness sont nécessaires.
Responsables : I1 pour revue, I4 pour preuves/corrections ; COMMAND (C),
PROJECTION (P), FOUNDATION (F) et WEB (W) ci-dessous selon impact effectif.

| ID exact | Contribution disponible et manque | Action locale / oracle de clôture | Slices pressenties et dépendance |
|---|---|---|---|
| L2M-C04-K | Identity/takeover/READ finaux + E1/E3 ; corpus composite répété Command réel + READ incomplet | Répéter takeover, rollback, interruption pendant travail ; réconcilier effets et publications aux seules finalisations committées, aucune duplication/perte durable | C+P ; harness de fautes et témoins SQL extérieurs |
| L2M-E01-E | Tests terminaux sans compteur extérieur de toutes familles ; E1 CHANGED seulement | Command UNCHANGED/REJECTED et Identity rejetée : seules complétions avancent, aucune position de mutation consommée ; snapshots physiques avant/après | C ; producteurs réels et positions par famille |
| L2M-C01-A-REPORT | Filtre User/4 K et snapshots limités | Plusieurs événements Identity pertinents entremêlés, partitions peuplées ; ensemble exact slots et K par partition, pas simple total | C+P ; peuplement déterministe multipartition |
| L2M-C01-D-REPORT | Publication concurrente prouvée ; Discovery READ non couplée | Retenir vraie publication sur compteur puis exécuter vraie Discovery READ avant/après commit et rollback ; préfixe sûr et K/slots exacts | C+P ; barrières SQL réelles |
| L2M-C04-L-REPORT | Anciens outils désactivés et corpus de candidats writers | Revue sémantique SQL direct/indirect/admin ; attribuer suppression/finalisation et garde ; refus physique hors succès ou reset READ coordonné | C+P, F si contrat transactionnel corrigé ; inventaire des autorités |
| L2M-C06-M-REPORT | Suffixe Identity contrôlé, transactions/compositions candidates | Inventorier tous writers, tests et Monolith compris ; chaque transaction physique liée à ownership/poisoning/garde et preuve de rollback | C+P+F selon correction ; suivi des appels indirects |
| L2M-E01-H-REPORT | Six interleavings Registration ; pas charge mixte de sûreté | Producteurs applicables concurrents, transactions lentes, aborts et reconnexions ; réconciliation toutes familles sans doublon/partiel. Charge de sûreté, pas benchmark SLO | C, P pour effets suivis ; ordonnanceur déterministe |
| L2M-E02-J-REPORT | Candidate E3-06 au SHA 230feb05 : SIGKILL après HTTP202 durable, Command et Registration uniques après lease | Revue des témoins et comparaison des entrées au gel ; accepter ou demander complément sous décision dédiée. Aucun crash pendant calcul ou READ crédité | C+W pour admission observée ; I1 peut proposer clôture sans rejouer la candidate recevable |
| L2M-E01-G-INV-COMP | Contrôles Invitation/Command ; graphe concurrent absent | Forcer attente INVITATION puis COMMAND_COMPLETION en commit/rollback ; capturer arêtes et exclure inverse sur tous chemins concernés | C ; observateur pg_blocking_pids et barrières |
| L2M-E01-G-ID-IDCOMP | Contention premier compteur Identity, pas graphe des deux | Contention contrôlée IDENTITY puis IDENTITY_COMPLETION ; attentes attestées, densité après résolution, revue des arêtes inverses | C ; même oracle extérieur que précédent |
| L2M-CON-05-CRASH | E3 avant claim/calcul ; pertes d'ACK autres frontières | Crash Command/READ avec claim en cours sans cession ; attemptCount inchangé, slot conservé puis reprise après expiration réelle | C+P ; processus distincts et délai dérivé du bail SQL, pas expiration artificielle |
| L2M-CON-05-NOPURGE | Refus reset partiel et outils historiques neutralisés | DELETE isolé hors réussite refusé, slot inchangé ; croiser l'inventaire administratif complet, y compris voies indirectes | C+P ; rôles runtime et privilèges réels |
| L2M-C03-J-CRASH | ACK inconnu/connexions coupées ; processus non tué pendant travail | Tuer pendant calcul/finalisation Command et READ ; rollback physique, slot récupérable et reprise, aucun effet partiel | C+P ; points de suspension test-only si nécessaires, sans endpoint public de contrôle |
| L2M-R8-03 | Contre-preuve d'absence administrativement injectée et candidats | Fermer inventaire writers et disparition de slot derrière K ; preuve garde/refus et cohérence checkpoint+effets ; pas de purge autorisée par le benchmark | C+P ; même inventaire que C04-L/NOPURGE |
| L2M-R8-05 | 2 704 références dans 239 fichiers au bilan L7.4 ; recherche non exhaustive | Auditer SQL construit dynamiquement, appels transitifs et admins ; définir pouvoirs admis, vérifier protections et corpus actif intégral | C+P, F si nécessaire ; revue sémantique indépendante |

**Correction requise** : les stimuli, oracles et couverture d'outillage doivent
être complétés sur 14 lignes. Aucun défaut produit déjà établi n'est inventé.
Si la revue découvre une voie de purge ou un défaut transactionnel actif,
correction productive puis requalification des consommateurs réellement affectés
sont bloquantes A ; ne pas « corriger » seulement l'attendu du test. E02-J reste
une candidate documentaire. Zéro ligne n'est affectée à C dans ce groupe de 15.

## 4. SPEC-09 : conserver les exigences, changer le périmètre revendiqué

La [vue de faisabilité 31 lignes](L7_Local_Baseline_SPEC09_Feasibility_Matrix.csv)
référence chacun des IDs de la [matrice contractuelle](L7_I4_SPEC09_Contract_Matrix.csv),
avec faisabilité, verdict d'obligation, preuve locale disponible, travail et
réserve. Elle ne copie pas les seuils comme s'ils étaient de nouveaux objectifs.

Les 13 familles restent identifiées : F01-A, B, C, D, E, F, G, H, I, J,
A11-L/M, ENDURANCE et SATURATION. Les **treize restent NOT_RUN dans leurs
conditions contractuelles/planifiées**. A/B requiert le socle matériel absent ;
les fonctions sans seuil (contention, repos, conservation) peuvent en revanche
être prouvées localement pour leurs invariants, sans certifier la famille A/B.
La vue distingue ces sous-preuves des familles entières ; elle conserve les
propositions §24.6, paramètres de banc et seuils normatifs à leur statut exact.

Seuils normatifs conservés : moyen 1/s Pot complet et Balance exacte p95≤500ms ;
10/s Pot p95≤1s et backlog stable ; 50/s p95≤3s puis drainage ; grand 1/s p95≤2s ;
Registration fraîche jusqu'au 200 `/me/pots?limit=1` avec nouveau binding p95≤500ms ;
rattrapage 10 000 commandes sur moyens indépendants sous nouvelles 1/s en ≤300s.
La taille locale moyen 20/200 ou grand 100/2 000 ne remplace pas les volumes
globaux 100/10k, 10k/1M et 100k/10M événements effectifs. Ni quotas de conteneurs
ni VM supplémentaires sur 16 Go ne fabriquent deux machines dédiées 4/8.

Les règles de répétition ≥3 et ≥300 observations valides/population SLO sont
conservées dans le plan courant ; SPEC-10 §24.6 précise encore que modalités,
échantillons/durées/polling doivent être ratifiés avant certification. Cette
tension documentaire ne permet ni d'abaisser ces minima ni d'annoncer qu'ils
sont ratifiés par le présent cadrage. Un profil local peut les utiliser pour
répétabilité sans recevoir de certificat SPEC-09.

## 5. Réserves héritées et décision de publication

| Origine conservée | Traitement proposé pour la baseline | Différé distinct |
|---|---|---|
| Quota READ Q=2 / FIND-L4-01 et lease240s / FIND-L2-01 | Mesurer latence/reprise sans hausse/réduction ad hoc ; B seulement si parcours et reprise promis tiennent avec délai documenté ; sinon A | Dimensionnement/SLO externes C ; pas de fermeture par changement de ressources |
| FIND-L4-03 et AUD-V3-013, openComponent FIND-L1-01 | Profil coûts local avec volumes réellement préparés ; absence d'oracle grand profil annoncé = A ; coût connu sans garantie non tenue peut être B | Profil/volumes/SLO A manquants C, chiffres historiques non extrapolés |
| RES-L6-I2-01 qualification locale ; 02 continuité WRITE ; 03 READY | Rejouer sur cinq JVM les scénarios locaux annoncés, oracle conservateur et continuité effectivement observée ; A si faux READY/perte | Continuité distribuée, incident site/cloud C |
| RES-L6-I2-04 sécurité outillage | Loopback, propriété cible, secrets et reset protégé obligatoires A | TLS/mTLS, rotation et exploitation production C |
| RES-L6-I2-05 perf ; 06 coût ; 07 certification globale | Coûts/temps mesurés et garanties locales délimitées ; global gates locales dues au gel | SPEC-09 et certification globale SPEC-10 incomplètes restent C et NOT_CERTIFIED/NOT_RUN appropriés |
| AUD-I5-01, R-AUD-I3-03 et autres réserves L2/L3/L4 citées historiquement | I1 rapproche chaque ID de son dernier acte avant de le déclarer ouvert ; A seulement si l'invariant requis reste non prouvé. r05/r12 sont QUALIFIED/active=false dans le registre, R1/R2 clos par le contre-audit, sans réouverture ici | Les FAIL anciens restent attribués ; clôture des deux réserves respectée. Leur inclusion dans les gates requis demeure obligatoire ; pas de dispense par quarantaine historique |
| Retrait V2/noms/schémas, contrats publics et provenance | Inventaire du code actif et cohérence du paquet public ; noms v3 résiduels évalués contre garantie annoncée ; contrat de convergence L7 maintenu | Une réserve de noms purement interne pourrait être B locale acceptée ; ne clôt pas ARC-13 ni L7 global |

**Aucune réserve B ou C n'est acceptée ici.** La politique A/B/C, leurs
propriétaires, critères et décision de gel figurent dans la
[définition du jalon](L7_Local_Baseline_Milestone_and_Acceptance.md).
Avant publication, toute variante non allouée ou critique sans oracle reste A.
Le choix local permet de travailler dès maintenant ; il n'autorise pas à
rebaptiser une lacune de sûreté « qualification cloud différée ».
