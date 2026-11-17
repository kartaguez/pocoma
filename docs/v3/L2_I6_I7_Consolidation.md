# L2 / I6 — Consolidation et dossier pour I7

**Verdict de livraison : READY WITH EXPLICIT RESERVATIONS.** I6 est terminé sur
son périmètre documentaire et de preuve ; **I7 — audit indépendant à réaliser**.
L2 demeure **INCONCLUSIVE, NON ACCEPTED**. Ce dossier prépare l'audit ; il ne le
réalise pas et ne remplace aucune décision humaine de clôture.

**I5 — CLOSED BY HUMAN ACCEPTANCE WITH AVAILABILITY RESERVE.**
AUD-I5-01 et R-AUD-I3-03 ne deviennent pas PASS. La configuration 30 secondes
reste **NON CERTIFIÉE** ; aucun certificat global 90s ni garantie de disponibilité
sous contention arbitraire. La réserve autorise la poursuite du développement,
sans homologation production. **Aucune nouvelle campagne temporelle I5 exécutée.**

## 1. Mandat, autorités et frontière de vérification

Mandat humain intégré I6/préparation I7, 29 octobre 2026, dépôt
`kartaguez/pocoma`, branche `feat/v3-architecture`, SHA initial exact :
**`5ab89dd3e58a7bfd617cdd6526f730e6d6854c80`**. Le worktree cible
`/private/tmp/pocoma-v3-postaud` était propre à cette référence ; le distant a
été reconfirmé identique. Le checkout principal `feat/runtime-monolith`, ses
modifications préexistantes et les autres worktrees sont préservés.

Référentiel : [plan directeur](V3_Implementation_Master_Plan.md),
[plan L2 §14](L2_Design_and_Verification_Plan.md#14-vérification-future-gates-provenance-et-critères-de-sortie),
[SPEC-03](SPEC-03_Events_Requests_Consumables.md),
[SPEC-05](SPEC-05_Consumption_Discovery.md),
[SPEC-07](SPEC-07_Transverse_Requirements.md),
[SPEC-08](SPEC-08_Observability.md), [SPEC-09](SPEC-09_Performance.md),
[SPEC-10](SPEC-10_Test_Certification.md),
[consolidation R1–R10](L2_R1_R10_Normative_Consolidation.md),
[décisions A01–A12](L2_Decision_Register.md), plans/rapports/manifestes I1–I5,
corrections et addenda post-audit, puis code, SQL et collecteurs effectifs.
La [politique Reactor §34](../testing/Reactor_Verification_Policy.md#34-proportionate-certification-and-evidence-reuse),
la [décision I5 et réutilisation](../testing/Certification_Evidence_Rationalization.md)
et le [nettoyage final du HEAD](../testing/Evidence_HEAD_Cleanup.md) s'appliquent.
Les empreintes des entrées consultées sont dans le [relevé de provenance](../testing/evidence/L2-I6/Proof-index.json).

Déclaration avant modification : impact productif attendu/observé **NONE** ;
slice primaire **NONE**, secondaires **NONE** ; DOCUMENTATION / PROOF-CHECKER.
Interdits : `app/`, protocoles, SQL, POM, configuration, anciens collecteurs,
oracles applicatifs et SPEC normatives. DB **NONE**, migrations **NOT RUN**,
franchissement inattendu **NONE**. Architecture et full reactor : **NOT REQUIRED
pour ce delta / NOT RUN dans I6** ; **toujours requis à G3** dans les conditions
du §9. Aucun mécanisme I2–I5 reconstruit, aucun nouveau correctif productif.

## 2. État effectif I1–I5

Le mandat humain accepte I1–I4 pour poursuivre. Les verdicts techniques ci-dessous
gardent leur périmètre ; les mentions historiques « audit à venir », « futur » ou
« non implémenté » ne révoquent pas ce mandat et ne créent pas de nouveau travail.

| Étape | État et preuve disponible | SHA réellement testé / limite |
|---|---|---|
| I1 | CLOSED / LOCAL BOUNDED PASS : quatre tables, routage, bootstrap, droits, isolation et 33 méthodes | COMMAND/READ/monolithe : `12eaa5ae983c23a4b07c0299ce59c4d7933bfb5d` ; architecture : `6835b5c15cc40d09c22e0b0a4f3b6538ac49992d`. Ne pas attribuer les quatre gates au seul second SHA. [Index I1](../testing/evidence/L2-I1/Gate-index.json) |
| I2 | CLOSED / LOCAL BOUNDED PASS : Engine/owner physique, participant Command, R1/R9, guards/poisoning, régressions | `4f2264ae6e11e7e6e1511577064bca5e31ad1336` ; quatre gates de livraison, 142 méthodes I2 et neuf owner. [Rapport I2](L2_I2_Execution_Report.md) |
| I3 | CLOSED / LOCAL BOUNDED PASS ; corrections R-AUD-I3-01/02 vérifiées | Initial `1d7cf51c13671c7c85a5c9c1281f526be2b2abfb` ; correctif `3ee575940669feb56b73b10122b5a3406bce6a08`. Conserver les deux observations distinctes. [Correctifs I3](L2_I3_Post_Audit_Corrections_Report.md) |
| I4 | LOCAL BOUNDED PASS : Discovery, 49 méthodes et sous-variantes physiques ; quatre gates sans filtre | `0b08d3bf0bfce1334260cfd5249b13c3040a67bb`. Command/POT/COMMAND_COMPLETION disponibles ; autres sources de fixture. [Rapport I4](L2_I4_Execution_Report.md) |
| I5 initial | Gates historiques C/R/M/A/F PASS, sûreté F01–F05 et pilotes bornés ; **ancien C temporel invalidé par l'audit** | `9a315e16567042d0b9bd017486d9043216b3ecd7`. Ce PASS de commandes n'est pas un certificat temporel. [Rapport historique](L2_I5_Execution_Report.md), [plan issu de l'audit](L2_I5_Independent_Audit_Corrective_Plan.md) |
| I5 correctif métrique/temporel | AUD-I5-02 implémenté, `i5_m03` PASS local ; gate composée FAIL, pas de clôture technique globale de ce finding | Gate `05b74814abe810a22f1f6a4c5125d4374325f6fa`, ciblage `8990737ce0b6f7df155d7e80aa698657815dc5ef` : métrique PASS, r12 FAIL. [Correctif et arrêt](L2_I5_Audit_Remediation_Report.md) |
| I5 dernière tentative | **CLOSED BY HUMAN ACCEPTANCE WITH AVAILABILITY RESERVE** ; banc FAIL, conclusion INCONCLUSIVE | V2 `5eab2e94379ef40c3f9ff54c14a4d04a8f13dcbd` ; dernier gel `a2133b6b43a74999374bc26a222f70c7500fadda`. [Rapport final](L2_I5_Temporal_Final_Report.md), [décision humaine](../testing/Certification_Evidence_Rationalization.md) |

Les rapports de corrections transcrivent les findings indépendants I3/I5. Aucun
rapport autonome d'audit du code I4 n'est identifié parmi les fichiers suivis
consultés ; le GO humain de poursuite est acquis, sans inventer cet artefact.
L'auditeur I7 vérifiera les rapports externes disponibles et les rapprochements.

## 3. Matrice exhaustive et règles de verdict

La [matrice Coverage.tsv](../testing/evidence/L2-I6/Coverage.tsv) reprend **380/380
allocations**, **253 identifiants normatifs**, **324 obligations L2** et **56
reports**, sans modifier le manifeste prospectif. Chaque ligne conserve exigence,
invariant, stimulus, oracle, autorité, sujet, allocation, gate et lot receveur ;
elle ajoute implémentation, nature réel/fixture, verdict, références de méthodes,
SHA effectivement testé, récupération et réserve.

Les clés d'implémentation et les 260 observations référencées se développent dans
[Proof-index.json](../testing/evidence/L2-I6/Proof-index.json) : classe/méthode,
source Java, archive, état XML et SHA. Les identifiants de preuve du TSV sont les
clés de `evidence` ; `archives` porte empreinte, reçu et emplacement Git exact.
Un lien contributif désigne une preuve pertinente, **pas** une équivalence
automatique entre son scénario et toute l'obligation. Les oracles du TSV sont
les oracles normatifs attendus, à confronter aux traces archivées.

| Verdict sur l'allocation | Nombre | Signification dans ce dossier |
|---|---:|---|
| PASS | 26 | Allocations Command explicitement rapprochées d'exécutions réelles : terminalisation, injections atomiques, ACK perdus, publications et completion R1. PASS historique borné, jamais PASS global de l'exigence |
| INCONCLUSIVE | 293 | Contributions locales disponibles, ou contrôle documentaire R6 ; exhaustivité de toutes variantes/autorités non établie par cette consolidation. Cela n'annule pas les observations PASS de ces contributions |
| NOT RUN | 5 | Aucun scénario précisément attribué ici pour E01-D, E01-E complet, E01-G-INV-COMP, E01-G-ID-IDCOMP et E01-E-IDENTITY-REJECT ; pas de campagne créée pour remplir ces cases |
| NOT APPLICABLE | 56 | Hors périmètre de clôture L2 suivant l'allocation existante ; **NOT RUN dans le lot futur**, aucune dispense normative ni certification métier |
| FAIL d'obligation productive ouverte nouvellement démontrée | 0 | Les FAIL historiques de banc restent dans les preuves et le tableau ci-dessous ; ce zéro n'atteste pas l'absence universelle de défaut |

Le PASS est volontairement limité aux 26 allocations Command rapprochées ; les
autres lignes gardent la distinction **méthode PASS / suffisance de l'obligation
INCONCLUSIVE**. Ni une méthode alias, ni plusieurs répétitions de la même méthode,
ni le succès d'une fermeture Maven ne multiplient les garanties.

## 4. Synthèse G1/G2 par invariant

| Axe | Implémentation et preuves réelles | Verdict borné et réserve |
|---|---|---|
| Leases et claims, CON-03/R3 | `ConsumptionEngine`, `JdbcPartitionOwnershipStore`, `JdbcConsumptionSlotStore` ; `i3_c03_a`…`l`, `i3_acquisition_lease_clock`, `i3_lease_races`, w02/w09/w10 et r03/r07 | Primitives et interleavings PostgreSQL PASS historiques ; renouvellement de claim indépendant du propriétaire de partition. Complétude temporelle R10-08 non certifiée |
| Fencing | Verrou exclusif du slot puis `clock_timestamp()` distinct, token/état/échéance ; c14/c15, C03-E/F/G/H, w10/w11 | Validité lors de la protection finale, pas au début de transaction ; expiration après protection valide admise. Aucun droit tiré de la seule ownership ou d'un timeout |
| Atomicité et effets | `SpringAttemptTransactions`, `ExecutePotCommand`, store/observation Pot ; R1 completion unique avant suffixe, CON-04/06, E02, guards et poisoning I2 | Command/Pot et PostgreSQL réels, Identity fixture. Effets/résultat/source/DELETE/publications atomiques ; aucune certification Identity réelle ni projection métier |
| Discovery burnable | `DiscoveryEngine.discoverRequests`, références Command et admission JDBC ; B01…B18, tour UUID/U volatil, revalidation PENDING protégée | PASS local I4 ; pages filtrées/verrouillées, arrivée continue, capacité, Q, terminalisation et reprise testées. IDENTITY_REQUEST demeure fixture |
| Discovery non-burnable | Fenêtre RR cohérente, préfixe dense couvert, K/slots dans l'autorité des effets ; N01…N18/P01 | PASS local I4 ; deux sources productives disponibles. Les cinq familles du banc ne sont pas cinq producteurs métier certifiés |
| Séquences/checkpoints/slots | Suffixe POT→COMMAND_COMPLETION ; checkpoint d'examen, source immuable, aucune purge ; I2/I4 et E01/E02 | Deux séquences productives, cinq en analogues SQL selon scénarios. E01-D et paires Invitation/Identity restent insuffisamment attribués. E01-H complet INCONCLUSIVE |
| WRITE/READ | Deux bases, owners/ressources distincts, lecture préparatoire WRITE et final READ ; w15, r08, X09 et CON-06 | Frontières techniques réellement exercées sans 2PC. Les probes READ ne produisent ni PotVersion métier, ni Balance, ni Visibility, ni résultat READ métier |
| Capacité et équité | `WorkerCapacity`, navigation séparée SKIP LOCKED, deux boucles, n01…n08/w03…w08/w16/w19 | Plafonds M_C/M_W, admission softCap distincte, capacité réutilisable et occasions répétées sous hypothèses. Un appel Control synchrone lent retarde les autres ; aucune latence absolue ni absence de famine sous contention arbitraire |
| UNKNOWN/recovery/drain | Quatre observations R9, inspection I3, w11/w14/w17…w21 et ressources r09 ; F01/F03/F04 | Sûreté conservatrice historique ; fin locale/Future/expiration ne prouvent pas résolution physique. UNKNOWN de préparation/recovery peut retenir la capacité et empêcher STOPPED jusqu'à terminaison/restart ; disponibilité non bornée |
| Observabilité | Micrometer, m01/m02, tours burnables u11/u12/m03 et correction `V3DiscoveryLoop` | AUD-I5-02 : émission après `tourFinished()` confirmé, négatifs omission/prématuré ; m03 PASS local au gel8990737c. Gate composée corrigée finale NOT RUN ; pas de clôture indépendante inventée |
| Pilote disponible | Profil opt-in `v3-consumption-worker-dev`, `worker-dev.py`, grants/bootstrap, Command réel et deux probes READ | Source/lanceur/runbook disponibles ; démonstration historique du jar7296e11c, 888 classes équivalentes à9a315e16. Ce jar historique précède le correctif métrique ; aucun nouveau jar ni lancement I6, aucune homologation DEV générale/production |

**G1 : INCONCLUSIVE en qualification complète.** P1–P6 possèdent des preuves
locales substantielles et récupérables ; les allocations sans preuve précise et
les rapprochements sémantiques encore partiels empêchent un passage global.
**G2 : INCONCLUSIVE / contributions techniques avec réserves acceptées pour
poursuivre.** Pilote, sûreté et ressources A/B ne satisfont pas à eux seuls C,
la clôture intégrée, toute l'observabilité et la qualification complète P7/P8.

Le plan P0 évoquait COMMAND/EVENT/PROJECTION. Les campagnes livrées ont utilisé
COMMAND, preuves directes READ JDBC/monolithe, architecture et parfois full selon
le graphe réel. Aucun gate V3 EVENT/PROJECTION isolé n'est inventé. Ces runtimes
métier absents ne deviennent pas des slices affectées par I6 ; la recevabilité
du rapprochement au texte G2 reste un point précis de revue I7/G3.

## 5. Corrections et constats I6

**Aucune correction de code, de test applicatif ou de protocole.** Aucun nouveau
défaut productif critique démontré ; le périmètre BLOCKED BY DEMONSTRATED DEFECT
n'est donc pas déclenché par ces contrôles. Le nouveau vérificateur ne lance ni
Maven ni les scripts contenus dans les archives.

| Constat | Traitement minimal, sans réécriture historique |
|---|---|
| Statuts P0/manifeste figé encore NOT RUN malgré exécutions ultérieures | Overlay daté, résultats liés aux reçus effectifs ; anciens textes conservés comme allocations ou observations historiques |
| I1 ne possède pas un unique SHA pour ses quatre commandes | Attribution exacte de chacun ; production et correction d'inventaire distinguées |
| `l2m_e01_h()` appelle c16 comme E01-B/C/G-POT-COMP | L'XML PASS prouve ce scénario ; il ne démontre pas toute E01-H (charge répétée, aborts/redémarrages). Obligation complète INCONCLUSIVE, sans nouvelle campagne |
| c12/c23 prospectifs non présents sous ces noms dans `L2ManifestProof` | Liens vers cession/quarantaine/reprise productives I3/I5 ; pas de PASS obtenu des anciennes cessions SQL manuelles. Suffisance complète laissée contradictoire |
| Résultats initiaux C/R/M/A/F coexistent avec audit temporel défavorable | r05/r12 historiques exclus des supports de PASS temporel ; FAIL v1/v2/final conservés distinctement |
| `app/mvnw.cmd` archivé en CRLF alors que le blob Git est LF | Vérification explicite de ces deux empreintes pour ce seul wrapper Windows ; aucune normalisation Java/SQL/oracle. `./mvnw` reste le wrapper exécuté sous macOS. La première vérification stricte a refusé cet écart ; correction du vérificateur, pas de la preuve |

Les sept tests du nouveau vérificateur couvrent notamment perte/doublon
d'allocation, promotion de fixture/report/réserve, FAIL/SKIP et exception CRLF
strictement limitée. Le contrôle mécanique ne prononce pas la suffisance
sémantique de ses propres liens contributifs.

## 6. Provenance, réutilisation et récupération

[Provenance.json](../testing/evidence/L2-I6/Provenance.json) inventorie les deltas
par SHA et catégorie. **`app/` et `scripts/v3/` de la baseline sont identiques au
dernier gel `a2133b6b43a74999374bc26a222f70c7500fadda`.** Depuis le gel initial
9a315e16, le seul fichier productif modifié est `V3DiscoveryLoop.java` pour la
métrique ; depuis05b74814, aucun delta productif/POM/SQL, mais sept fichiers de
test diffèrent. Ces changements temporels ne sont jamais présentés comme testés
par l'ancien full reactor. Le relevé détaille aussi les changements plus larges
I1/I2/I3/I4 : leurs arbres complets ne sont pas réputés identiques au HEAD.

Les régressions héritées ont été réexécutées dans les campagnes ultérieures :
I5-F contient 420 méthodes requises PASS sur son gel ; la gate composée05b74814
contient 405 méthodes requises dont **404 PASS et r12 FAIL**. Ces observations
restent attribuées à ces SHAs. Les tests temporels modifiés, seuils contestés et
changements d'hypothèses ne bénéficient d'aucun transfert de PASS. Les 15
déterministes v2 et W1 historique restent bornés par [reuse.json](../testing/evidence/L2-I5-temporal-final/reuse.json).
Java21/PG17.11 et paramètres exacts restent ceux des captures ; aucune
équivalence avec un nouvel environnement n'est présumée, aucun runtime lancé.

Réutilisation : mêmes invariants et entrées inchangées explicitement identifiées,
preuves accessibles/intègres, mêmes oracles et réserves ; ni certificat au SHA
I6, ni remplacement d'une gate fraîche expressément due. Le contrôle documentaire
de qualification et son SHA propre figurent dans [Verification.json](../testing/evidence/L2-I6/Verification.json).
Les identités des arbres applicatifs et des scripts de campagne sont vérifiées
entre baseline, qualification et publication ; les nouveaux scripts I6 portent
leurs propres empreintes. Le SHA publié exact est fourni après push, sans
auto-référence Git circulaire dans ce rapport.

Les neuf archives relues couvrent I1-A, I2-C, I3 corrigé-C, I4-C, I5-F, gate
corrective en échec, ciblage R2, v2 et finale. Le relevé de vérification compte
les XML, états et empreintes ; il ne les additionne pas comme tests uniques.
Les cas requis, **y compris les FAIL**, sont contrôlés exactement une fois dans
leur archive. Les empreintes internes des formats à `result.json` et les entrées
sources des reçus disponibles sont recoupées avec les blobs de leur SHA.

Les 105 archives restent hors HEAD. [Evidence_Recovery.md](../testing/Evidence_Recovery.md)
et [Evidence_Retention.json](../testing/Evidence_Retention.json) portent les
locators exacts et SHA-256, y compris les anciens alias gzip. Exemple de lecture,
sans relancer une campagne :

```sh
python3 -B scripts/testing/evidence-storage.py resolve docs/testing/evidence/L2-I5/F-9a315e16.tar.gz
python3 -B scripts/testing/evidence-storage.py restore docs/testing/evidence/L2-I5/F-9a315e16.tar.gz --destination /private/tmp/i7-F-original.tar.gz
python3 -B scripts/testing/check-v3-l2-i6.py --archives
```

La destination doit être nouvelle ; le vérificateur utilise un répertoire
temporaire et lit les membres TAR sans les exécuter. Aucun stockage externe ni
SLA de sauvegarde inventé. Les ancêtres de rétention doivent rester accessibles ;
les neuf diagnostics anciens sans SHA univoque signalés par le nettoyage ne
deviennent pas des preuves qualifiées I6. Aucun rapport/manifeste historique
n'est modifié, aucune SPEC changée, aucune nouvelle archive ajoutée.

## 7. Régressions et contrôles réellement exécutés

**Nouvelles exécutions : contrôles documentaires/outils seulement.** Les résultats,
commandes, codes retour, empreintes et durées mesurées sont dans
[Verification.json](../testing/evidence/L2-I6/Verification.json). Depuis la racine :

```sh
python3 -B -m unittest scripts/testing/test_evidence_storage.py scripts/testing/test_i2_evidence.py scripts/testing/test_i3_evidence.py scripts/testing/test_i4_evidence.py scripts/testing/test_i5_evidence.py scripts/testing/test_summarize_v3.py scripts/testing/test_l1_provenance.py
python3 -B docs/v3/check_l2_manifest.py
python3 -B scripts/v3/check-routing-vectors.py
python3 -B scripts/testing/evidence-storage.py check
python3 -B -m unittest scripts/testing/test_i6_evidence.py
python3 -B scripts/testing/check-v3-l2-i6.py --archives
git diff --check
git diff 5ab89dd3e58a7bfd617cdd6526f730e6d6854c80 -- app scripts/v3
git ls-remote origin refs/heads/feat/v3-architecture
```

Qualification documentaire au SHA **`127d59834aa8702b7735a5f02f6dc1f6328b0a7c`** : sept tests I6 PASS,
neuf archives relues, 1 657 cas requis contrôlés (répétitions inter-campagnes
incluses) et 3 820 empreintes d'entrées de reçus disponibles rapprochées du Git
historique, avec trois représentations CRLF explicites du seul wrapper Windows.
Aucun nouveau PASS applicatif.

Les 39 tests d'outils préexistants, le catalogue L2 et les 21 vecteurs ont été
contrôlés à la baseline ; les nouveaux contrôles I6 sont qualifiés au commit
indiqué dans le reçu. Aucun de ces tests Python n'exécute les campagnes Java.
Maven, PostgreSQL, Docker, packaging, démonstration, migration historique et
renewal temporel : **NOT RUN dans I6**. Les anciennes commandes canoniques sont
des preuves **REUSED**, avec leurs skips conservés :
`TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`
reste SKIP, jamais PASS. Les avertissements Surefire et FAIL diagnostiques
historiques restent dans leurs captures.

## 8. Coûts et réserves résiduelles

[Certification-costs.json](../testing/evidence/L2-I6/Certification-costs.json)
recopie les durées réellement reçues, sans estimer un coût « économisé » : I2
gates finaux1 159,583s ; I3 corrigé1 354,167s ; I4 2 944,412s ; I5 initial
13 714,183s de durées d'invocations additionnées. Plusieurs gates étaient
concurrents et partagent des méthodes : **ces sommes ne sont ni une durée murale
globale ni un nombre de tests distincts**. Diagnostics/lecture/rédaction ne sont
pas tous chronométrés. Correctif I5 :2 960,688s ; v2 :94,642s de campagne et
41,624s Maven déterministe ; dernière tentative :107,229s de campagne et
18,353s Maven préalable. Aucune de ces tentatives n'est rejouée.

Coût applicatif additionnel I6 : **zéro invocation Maven/PG**, zéro campagne
temporelle, zéro gate globale. Le reçu mesure séparément le temps des contrôles
Python et de lecture des archives. Les seuls nouveaux objets suivis sont des
documents, tableaux/reçus compacts et un vérificateur avec ses tests.

Les coûts SQL locaux I4 et I5 restent descriptifs : par exemple P02 Command
N10000/page32, médiane20,160ms et maximum24,920ms ; lock partition borné par
instrumentation18,265/22,953ms. COUNT reste proportionnel au stock/index,
WAL global peut contenir d'autres activités ; aucun O(1) temporel, percentile E2E
ou SLO SPEC-09 certifié. Les performances intégrées sont différées à L6.

| Réserve | État conservé / conséquence |
|---|---|
| AUD-I5-01 et R-AUD-I3-03 | **INCONCLUSIVE, non PASS** ; disponibilité/reprise bornée non homologuées, attente d'expiration et drainage conservateur acceptés pour poursuivre |
| v1 R1 dépassé, v2 témoin d'accessibilité insuffisant, final préparation retenue au-delà du transaction_timeout puis STOPPED non acquis | **FAIL des bancs conservés**, pas nouvelle violation productive de fencing/atomicité démontrée. Marqueur PASS antérieur au cleanup insuffisant |
| AUD-I5-02 | Correctif et m03 PASS local, mais gate finale corrigée NOT RUN ; clôture technique/audit indépendante non acquise par I6 |
| Couverture exhaustive | Cinq allocations sans attribution précise ; autres rapprochements partiels ; aucune campagne automatique pour les satisfaire |
| Réel/fixture | Identity/Invitation L3/L5 ; projections/HTTP L4/L5 ; REC-01/READY, charge/SLO L6 ; convergence/distribution L7/L8. Aucun PASS métier dérivé des probes |
| Preuves | Historique Git récupérable, pas sauvegarde externe garantie ; diagnostics à SHA ambigu exclus des conclusions |

## 9. G3 : conditions satisfaites et manquantes

| Condition du plan L2/R6 | État actuel |
|---|---|
| I1–I4 réalisés et acceptés pour poursuivre | Acquis par mandat humain, avec preuves bornées |
| I5 clôturé pour poursuite | Acquis **avec réserve de disponibilité**, sans nouvelle boucle I5 |
| Dossier I6, attribution des 380 allocations, sources et récupération | Livré ; contrôles mécaniques vérifiés, limites sémantiques visibles |
| Passage global G1/G2 | **INCONCLUSIVE**, non prononcé ; insuffisances et réserves ci-dessus |
| I7 indépendant, contradictions et corrections nécessaires vérifiées | **NOT RUN / MANQUANT** ; ce travail n'est pas son propre audit |
| Architecture globale + full reactor pour G3 | **REQUIS À LA CLÔTURE MAJEURE, NOT RUN I6** ; préconditions G3 non réunies. PASS historiques à9a315e16 disponibles, pas gates fraîches du HEAD |
| Décision humaine explicite L2 ACCEPTED et périmètre accepté | **MANQUANTE**, aucune décision déduite du GO I6/I7 |

Les commandes canoniques de clôture, depuis `app/`, restent
`./mvnw -pl architecture-tests -am test` et `./mvnw test`. Elles ne sont pas
exécutées ici : I7 n'est pas réalisé et leurs fermetures actuelles incluent
`V3WorkerResourcesPostgresTest.i5_r05/i5_r12`, donc une nouvelle campagne
temporelle explicitement interdite par le mandat. Aucun filtrage/skip n'est
introduit pour fabriquer une gate PASS. L'articulation d'un futur G3 avec cette
interdiction et avec ses exigences de fraîcheur devra être décidée humainement
sur le dossier d'audit ; aucune dispense ni campagne supplémentaire n'est
déduite de cette remarque. Il ne s'agit pas d'une nouvelle étape de planification.

## 10. I7 — Audit indépendant à réaliser

L'auditeur devra produire un rapport distinct, sur un SHA publié identifié,
en discutant contradictoirement les points suivants. Cette liste n'autorise
aucune campagne temporelle I5 supplémentaire.

1. Vérifier identité baseline→qualification→publication de `app/`, SQL, POM,
   scripts de campagne, contrat de routage et paramètres ; contrôler les deltas
   de tests et l'unique correctif productif métrique depuis9a315e16.
2. Reconstituer les 380 allocations sans perte/report transformé en PASS ;
   discuter les 26 PASS Command et les rapprochements INCONCLUSIVE, notamment
   E01-H alias c16, E01-D/E et paires de familles absentes, c12/c23 et les méthodes
   prévisionnelles remplacées. Ne pas confondre exactitude du lien et suffisance.
3. Contrôler C03-A…L individuellement et leurs variantes Worker : horloge après
   attente, token remplacé, expiration après verrou valide, A→B→A, claim survivant
   à la perte de partition et absence d'acquisition par ancien propriétaire.
4. Contrôler R1 : completion une fois/clé exacte/thread/unité, appel avant
   suffixe, ordre slot→Identity→Pot, contraintes différées, writer_xid, fermeture
   et poisoning y compris erreurs interceptées/callbacks JPA tardifs.
5. Contrôler la physique CON-06 : même connexion/PID/XID lorsque requis,
   un owner final, mauvaises sources/managers/ambiantes/transactions intérieures
   refusés ; aucun 2PC ni double writer dans le profil réellement activé.
6. Contrôler fenêtre sûre et préfixe sous cap : pas de MAX hors snapshot,
   K d'examen, atomicité K/slots et absence de réenrôlement ≤K. Séparer les deux
   producteurs réels des cinq journaux de fixture.
7. Contrôler le tour burnable, U fixe/UUID unsigned, pages vides filtrées,
   sources sautées/revisitées, terminalisation et stratégie B sans cycle ;
   quarantaine existante et réactivation au-delà du softCap préservées.
8. Contrôler M_C/M_W/softCap séparément, pré-réservation et refus executor,
   restitution après fin locale **et** résolution physique, retour partiel,
   saturation et équité conditionnelle ; lire aussi les traces défavorables.
9. Contrôler R-AUD-I3-01/02 et F01–F05 : classement transport/configuration
   prioritaire, cession une fois après rollback certain, pas de budget supposé
   après UNKNOWN ; fin locale/cancel/absence de slot ne prouvent pas rollback.
10. Contrôler drainage et lifecycle : barrière de début, arrêt répété,
    callbacks seulement après STOPPED prouvé, pools conservés sous activité,
    UNKNOWN de préparation/recovery non résolu par inspection de slot seule.
11. Revoir **sans rejeu temporel** A/B/C WRITE∧READ, les contre-exemples v1/v2/final,
    contrat et fenêtres réduites/suspensions de la dernière tentative. Conserver
    FAIL du banc et INCONCLUSIVE temporel ; aucune garantie30s/90s/production.
12. Contrôler AUD-I5-02 : vraie fin de tour burnable confirmée, zéro sur page
    intermédiaire/capacité/rollback, négatifs omission et émission prématurée,
    labels/histogrammes bornés et observer fautif ; distinguer m03 PASS local
    et gate corrigée non acquise.
13. Vérifier le pilote réel, admission PENDING sans slot, résultat et publications
    uniques ; examiner opt-in, isolation/permissions, disponibilité des outils,
    identité du jar historique et limites Identity/READ explicites.
14. Restaurer au besoin quelques captures hors checkout, vérifier hashes, XML et
    variantes physiques ; contrôler les 105 archives toujours hors HEAD, leurs
    ancêtres accessibles et les skips/FAIL/UNKNOWN non effacés. Qualifier ensuite
    G1/G2 et la recevabilité des contributions face aux gates attendues G3.

## 11. Arbitrages humains et recommandation de clôture

L'audit devra permettre de statuer sur : recevabilité du périmètre technique
G1/G2 avec les allocations incomplètes ; portée de clôture d'AUD-I5-02 ; réserves
à transmettre explicitement aux lots suivants ; articulation G3/fraîcheur et
interdiction de nouvelle certification I5 ; enfin décision explicite de clôture
L2 et son périmètre. La réserve de disponibilité déjà acceptée **n'est pas à
réouvrir pour poursuivre I7** et ne déclenche aucun correctif de performance ou
de production dans ce mandat.

**Recommandation : transmettre ce dossier directement à I7, READY WITH EXPLICIT
RESERVATIONS.** La sûreté et les pilotes bornés ont des observations substantielles
récupérables ; les lacunes sont identifiées sans réimplémenter les mécanismes.
G3 reste non franchi. Une éventuelle acceptation bornée de L2 appartient au
responsable humain après audit indépendant ; aucune homologation production
ni acceptation L2 n'est prononcée ici. Pas de nouvelle passe automatique de
planification ou de certification I5 après cette publication.
