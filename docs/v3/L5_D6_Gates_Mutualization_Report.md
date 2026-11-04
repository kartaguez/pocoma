# Pocoma V3 — L5 — Compatibilité D6 : G5, Architecture et full reactor

Date : **4 novembre 2026**. Référence : **`9dd1c44720af61b66abdfb4fdaa7f3076ced8737`**, `feat/v3-architecture`. **MUTUALIZATION CONDITIONALLY VALID — proposition soumise à ratification humaine ; aucune gate exécutée, aucun GO I1.**

Portée : résoudre l'allocation des campagnes du [plan L5 §§12–13](L5_Supra_Entry_Audit_and_Implementation_Plan.md) et la condition de l'[acte D1/D6 §6](L5_D1_D6_Ratification_and_D4_Calibration_Decision.md), sans modifier cet acte, le [Master Plan](V3_Implementation_Master_Plan.md), les [SPEC/oracles](SPEC-10_Test_Certification.md) ou la [Reactor Verification Policy](../testing/Reactor_Verification_Policy.md). D6-composition reste acquise ; D6-topologie I4 reste hors de cette résolution.

## 1. Lecture normative et hiérarchie

La politique contient **deux §34** : « FOUNDATION » et « Proportionate certification and evidence reuse ». Les références §34 ci-dessous visent explicitement le second sauf mention FOUNDATION. Les sous-titres/tableaux sont cités pour éviter toute ambiguïté.

| Clause exacte de la politique | Exigence / permission et conséquence L5 |
|---|---|
| §§3/29/30, déclaration avant modification ; §15 Unexpected boundary crossing | Déclarer impact, slices, DB et gates avant code ; toute traversée imprévue est expliquée et redéclarée avant poursuite. Le périmètre ne se déduit pas après réussite d'une grosse suite. |
| §§5.2/6.2/8.2, ancres WEB/COMMAND/PROJECTION ; §§11/12/14 | Les commandes canoniques sont `-pl <ancres> -am test` non filtrées. Composer les consommateurs matériellement changés est permis ; closure de compilation ≠ capacité dont le comportement change. |
| §§16/17.2, Architecture tests / Global architecture proof | Architecture globale requise si dépendances, frontières, isolation ou interdictions changent ; commande `./mvnw -pl architecture-tests -am test`. Pas automatique à chaque pas ; requise pour la fermeture des frontières HTTP V3 à Q5. |
| §18 Full reactor | `./mvnw test` est la preuve complète d'intégration Maven ; « It MUST be run when the applicable integration gate requires it. » |
| §19 Full-reactor gates ; §21.2 WA / WAVE GATE | Major Wave, main, restructuration large, infrastructure transverse non bornable, stabilisation globale déclenchent normalement le full. Un petit WA ne le déclenche pas automatiquement. Cela ne dispense pas d'une gate explicite dans une autre clause applicable. |
| §21.1 STEP ; §21.3 INTEGRATION / MAIN GATE | Pas normal : slices locales, sans full/Architecture systématiques. Main : full + Architecture + DB applicable. Les checkpoints I1–I4 ne sont pas des clôtures canoniques indépendantes. |
| §34 Proportionate, tableau lignes Documentation / Architectural boundary / Lot closure | Documentation : slices NONE. Frontière : consommateurs + Architecture. Lot : slices non filtrées, variantes PG et provenance ; pas de full automatique pour chaque petit lot/document d'acceptation, mais une gate explicite n'est pas silencieusement dispensée. |
| §34 Proportionate, paragraphe « When several declared slices share upstream modules » | Une invocation composée est préférée si chaque ancre, méthode, variante et preuve physique est incluse et si le contrat n'exige pas des exécutions isolées indépendantes. Pas de preuve d'isolation déduite de la seule composition. |
| §35, introduction et tableau CERTIFICATION | Le niveau opérationnel conserve SPEC-10 et les commandes non filtrées. Déclencheur exact **« Required lot closure »** ; preuve **« Unfiltered full reactor »** ; **« No local budget caps this gate. »** La clôture requise L5/Q5 est concernée, même sans classification Major Wave. |
| §35, paragraphes sélection/lifecycle, Build commands and freshness, Evidence/selection controls | CERTIFICATION = `./mvnw --batch-mode --no-transfer-progress test`, sans filtre/module/skip. Une invocation composée résout ses projets amont sans install préalable. Modèle effectif, inventaire source, identités exactes, provenance fraîche/module/producer et zéro skip exigés ; BUILD SUCCESS seul ne suffit pas. |
| §§22–28 ; §34 FOUNDATION ; §34 Proportionate et §35 | Pas de rejeu historique séparé par prudence. Baseline V2 actuellement **NONE** ; une fermeture non filtrée peut tirer les migrations historiques existantes, à déclarer sans les masquer. Les variantes physiques requises et preuves externes ne disparaissent pas avec Maven. |
| §34 Proportionate, dernière phrase ; §35, Nominative quarantine | Modifier un oracle, seuil, allocation de variantes ou gate obligatoire exige **arbitrage humain séparé**. La quarantaine `i5_r05/r12` ne les retire pas des commandes canoniques. Pas de dispense technique fondée sur le coût. |

**Exigé :** retenir le full à Q5 selon la lecture conservatrice de §35 et Architecture globale selon §17 ; conserver tous les protocoles applicables. **Permis :** acquitter plusieurs obligations dans une exécution dont la couverture est démontrée, §34 ; reporter la fermeture globale aux checkpoints convenus sans la déclarer acquise. **Interdit :** filtres/skip/quarantaine implicite, XML anciens, mutualisation qui omet une variante ou un environnement indépendant, budget local utilisé comme dispense. **Humain :** ratifier cette allocation et le report Architecture à Q5 ; décider le calendrier/budget de campagne, les éventuelles variantes supplémentaires, toute véritable dérogation normative.

La proposition initiale « pas Major Wave donc pas de full L5 » ne suffit pas au regard de §35. Retenir un full à Q5 satisfait les deux textes sans changer la politique. La mutualisation est une allocation probatoire conditionnelle, **pas une dispense de full**.

## 2. Vérification statique du contenu effectif

Au SHA publié, [app/pom.xml](../../app/pom.xml) déclare **60 modules enfants**, dont les quatre ancres G5 et `architecture-tests` ; parent inclus, 61 projets. L'analyse XML des dépendances directes, tous scopes et dépendances optionnelles conservés, donne une closure G5 couvrant **les 60 modules** et aucun cycle. C'est une preuve statique du graphe déclaré ; **pas** `help:effective-pom`, inventaire JUnit résolu ni preuve d'exécution.

[architecture-tests/pom.xml](../../app/architecture-tests/pom.xml) référence les racines, y compris Monolithe/PROJECTION/WEB. Le full sélectionne réellement ce module par l'agrégateur ; sa présence au sommaire n'assure pas que ses tests seront exécutés si un module amont échoue. Le parent propose le profil de provenance Surefire/Failsafe, sans rendre `test` équivalent à tout lifecycle `verify`/`install`. Les nouveaux tests L5 n'existent pas encore : leur inclusion future est une **condition**, à contrôler au SHA final Q5.

Le graphe HTTP→engines et composition→HTTP/engines est sans retour HTTP→composition. Dépendances optionnelles : contrôler le classpath des racines, pas seulement celui de composition. Ces constats n'acceptent pas encore l'isolation runtime V3.

## 3. Comparaison des stratégies

| Stratégie | Preuve réelle / limites | Coût prévisionnel et recommandation |
|---|---|---|
| **A — G5 puis full natif** | G5 non filtrée + Architecture puis full sur le même SHA. Deux campagnes peuvent prouver des environnements/variantes **indépendants** si un contrat les exige. À entrées identiques, aucune obligation supplémentaire repérée n'impose ce double passage ; seule l'invocation native apporte formellement le full. G5 peut déjà parcourir les 60 modules. | G5 **65–85 min**, maximum historique proposé **100** ; full **70–90 min**, point de décision **100** ; soit **135–175 min** avant B5/extras, avec fort chevauchement. À utiliser seulement si une variante isolée requise ne peut entrer dans B ; pas par défaut. |
| **B — un full natif couvrant G5 + Architecture** | Recommandé sous conditions §4. Il inclut chaque ancre G5, chaque test L5, Architecture complète et PG requis dans le même lifecycle. §34 permet de ne tester les amonts partagés qu'une fois ; aucune clause identifiée n'impose une deuxième invocation Architecture/G5 à contenu déjà intégralement couvert. Ne prouve pas les protocoles externes absents de Maven. | **70–90 min prévisionnelles** pour la campagne Maven, **point de décision à 100 min**, B5 séparé. Conserver l'estimation G5 comme référence comparative, ne pas l'additionner. Aucun coût nouveau mesuré ici. |
| **C — G5 seule avec dispense du full** | Aucune dispense acquise. La politique ne délègue aucune autorité de dispense à l'agent, ni procédure automatique au seuil temporel. Une relaxation de gate exige l'arbitrage normatif humain séparé du responsable habilité du programme/politique, avec portée, risques, réserves et statut explicites. La politique exige un arbitrage humain mais ne nomme pas de titulaire individuel ni de droit automatique à dispense : faire identifier cette habilitation dans tout acte éventuel ; ce rapport n'en confère aucune. Le précédent L4 n'est pas transférable. | **65–85 min** + B5/extras mais ne satisfait pas actuellement §35. **Non recommandée** ; économie incertaine puisque G5 couvre statiquement les 60 modules. Si une autorité humaine veut ce choix, acte distinct et clarification normative préalables, aucun PASS full déclaré. |

## 4. Campagne canonique unique proposée à Q5

Depuis `app/`, **une seule invocation de tests Maven**, sélection/lifecycle canonique :

```sh
./mvnw test
```

Le collecteur existant peut porter cette invocation : depuis la racine, `python3 -B scripts/testing/verify.py CERTIFICATION --output /absolute/new/evidence-directory`. Il lance découverte du modèle puis **un seul processus Maven de tests**, `./mvnw --batch-mode --no-transfer-progress test`, augmenté uniquement des deux propriétés de provenance tracées. Ne pas exécuter ensuite la commande nue pour « compléter » le même full. Découverte du modèle ≠ seconde invocation de tests ; son coût est inclus dans la campagne. Aucun `clean install` ajouté, sauf obligation contractuelle distincte réellement applicable ; un artefact de distribution demande sa propre preuve.

Conditions cumulatives à contrôler **avant Q5**, pas par une campagne globale avant I1 :

1. Modèle Maven effectif au SHA final incluant WEB, COMMAND, PROJECTION, Monolithe, Architecture, toutes dépendances/options/profils nécessaires. Comparer à la closure G5 ; toute omission empêche la mutualisation pour l'obligation omise.
2. Inventaire nominatif de toutes méthodes/variantes L5 et de la fermeture non filtrée tiré des sources/configuration effectives. Tests L5 sous Surefire dans `test` ; un test seulement Failsafe, un protocole packaging/start/restart ou un profil non activé n'est pas couvert par le nom full. Traiter cette obligation séparément sans rejouer tout G5.
3. Architecture exécutée **intégralement**, zéro skip, avec les nouvelles règles L5 et tous les bytecodes requis. Échec amont qui la saute = gate non acquise, pas « full couvrant Architecture PASS ».
4. Autorités PostgreSQL/variantes READ/WRITE/physiques réellement requises disponibles, rôles, permissions, image/version, séparations, interleavings et hypothèses enregistrés ; ni H2 ni mocks de PG comme substitution. Inclure les quarantaines si la fermeture les sélectionne ; conserver leurs échecs et réserves.
5. Un seul propriétaire de checkout/rapports/processus/fixtures ; logs, modèle effectif, SHA dirty state et empreintes avant/après, rapports neufs isolés portant invocation/module/producer. Même ensemble exact d'identités attendues/exécutées, zéro manquant/skip/doublon, exit code réussi. Un collecteur incapable de découvrir une forme de test refuse PASS ; décision outillage ciblée avant campagne, pas import XML historique.
6. Matrice obligation→méthode/variante→preuve attribue **le même reçu** à G5, Architecture et full ; elle sépare les contributions historiques validement réutilisées, nouveaux résultats et obligations externes. Les contrats n'exigent aucune exécution isolée indépendante pour les obligations ainsi réunies. Si cela change, compléter la seule preuve non couverte après décision.

Ces conditions expliquent le verdict **MUTUALIZATION CONDITIONALLY VALID** : le mécanisme est recevable selon la politique, mais l'inventaire final et son exécution restent à établir et l'allocation à ratifier. Aucun full PASS anticipé.

## 5. Budget de fermeture, arrêt et obligations restantes

Références conservées : FULL-04-FAIL L4 **4 107,41769 s Maven / ~4 115,19 s campagne** (~68,6 min), [reçu](../testing/evidence/L4-I8/runs/FULL-04-FAIL/result.json) ; Architecture ciblée ~47,8 s, **pas** sa gate canonique ; diagnostic §35 `clean install` **3 079,751 s FAIL**, d'autre portée. Aucune de ces observations ne mesure le futur full L5. Ventilation coût L5/héritage/découverte/PG/collecte encore inconnue.

| Poste final Q5 | Réservation proposée, non mesurée | Arrêt / décision |
|---|---|---|
| Full natif unique tenant lieu de G5 et Architecture | **70–90 min**, préparation/découverte/fixtures/exécution/collecte incluses ; **100 min point de décision**, pas plafond normatif de certification | Préparer le watchdog pour arrêter proprement à 100 min en l'absence de prolongation humaine explicitement accordée avant l'échéance ; aucune obligation ne disparaît. Campagne interrompue = **NOT PASSED**, preuve conservée, résolution physique vérifiée et nouvelle décision de calendrier avant reprise. |
| B5 collection entière | **15–25 min**, maximum historique **40 min** inchangé | Indépendant de Maven ; polling et prérequis inclus, secrets hors preuve. Interruption/absence de scénario ≠ PASS. |
| Externes/variantes non couvertes | **X minutes à chiffrer par inventaire avant Q5**, aucun forfait fictif | AuthN réelle, consentement Link B3 et intégration B5, protocoles PG non sélectionnés, packaging/distribution si applicable ; réutiliser B3 seulement selon §34. Une obligation manquante bloque l'acceptation correspondante. |
| Diagnostic d'incident | **15 min initiales** selon plan global, sans nouvelle exécution | Cause, preuves, coût et décision ; aucun retry automatique. |

**Fermeture finale : 85–115 min prévues + X**, fenêtre d'organisation **100+40 = 140 min + X**, hors développement/revue/diagnostic. À X inconnu, ne pas annoncer de total ferme. Les plafonds T1/B1 15, T2/B2 20, T3 20, B3 45 et T4/B4 40 restent inchangés. Le full remplace le poste G5, il ne s'y ajoute pas ; risque de double paiement A **+65–85 min** sans nouvelle propriété s'il est lancé malgré cette allocation. Aucun mécanisme de relance n'est autorisé par ces estimations.

L5 n'acquiert ni certificat SPEC-10 global, ni qualification PERF-01/OPS-01/REC-01, ni production par ce seul full. Les preuves externes AuthN et consentement Link réels, la collection B5, les variantes physiques applicables et l'audit/acceptation humaine restent dus. Les réserves L1–L4 restent attribuées à leurs actes.

## 6. Arbitrages exacts à soumettre

Ratifier **B**, full dû à Q5 sous §35, simultanément G5 et Architecture sous conditions §4 ; approuver le report de la gate Architecture à Q5 pour des checkpoints I1–I4 sans clôture canonique autonome ; accepter le budget/arrêt §5 et l'inventaire des extras avant Q5. Cette décision est une **ratification d'allocation**, sans dispense de politique. Toute demande d'une clôture canonique d'incrément ou nouvelle traversée §§15/17/19 impose de revoir cette allocation avant poursuite. Aucun GO I1 implicite.

Vérification présente : slices **NONE**, DB **NONE**, aucun franchissement ; Architecture/full **NOT REQUIRED / NOT RUN pour le documentaire**. Git, lecture des clauses/POM/sources et analyse XML statique seulement ; aucun Maven, PostgreSQL, Docker, Bruno ou replay de gate ; aucun acte historique/politique/oracle changé, aucun commit/push.
