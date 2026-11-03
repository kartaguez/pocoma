# L4/I8 — Exécution et qualification READ

**IMPLEMENTED — COMPOSITE INTERNAL QUALIFICATION WITH EXPLICIT HUMAN DISPENSATION.** T01–T11 sont PASS. La qualification canonique/Architecture est composite et expressément autorisée : 2 024 tests hors Architecture PASS au SHA productif `a3470df6…` + 167 tests Architecture PASS au SHA d’oracles `8a007768…`, sans différence de code productif. **Aucun full PASS n’est déclaré ; L4 reste ouvert.**

SHA initial : `a2b4e2a07a64e33eafe9e81a0ddc25c7961dc9cc`, branche `feat/v3-architecture`, worktree initial propre. `origin` : `https://github.com/kartaguez/pocoma.git`, SHA distant identique vérifié avant modification. Les cinq autres worktrees restent intacts.

## Frontière déclarée avant implémentation

PROJECTION primaire ; BINDING et COMMAND secondaires ; ancres complémentaires Results et monolithe. Changements bornés aux compositions, settings, préflights, deux racines, façades READ et lecture CurrentPot ; dépendances minimales des modules existants. Architecture et full reactor REQUIRED au jalon I8. Base CURRENT V3 ; pas de campagne historique autonome, historique transitif des gates conservé. Aucun changement de ConsumptionEngine, SpringAttemptTransactions, algorithmes des participants, producteurs WRITE, routage ou migrations.

La [comparaison des entrées](../testing/evidence/L4-I8/Application_Input_Comparison.json) identifie 1 372 fichiers applicatifs identiques octet par octet entre le worktree principal et le checkout qualifié. Seul `app/mvnw.cmd`, non exécuté sous macOS, diffère par LF/CRLF ; son contenu normalisé est identique au blob Git du SHA testé. Le wrapper Unix `./mvnw` et toutes les entrées exécutées sont identiques. Aucun fichier n’a été réécrit pour éliminer cet écart de checkout.

## Adaptation locale du placement des tests

Le plan prescrit une dépendance de test monolithe → runtime PROJECTION pour T11, et demande aussi de charger la vraie main monolithe depuis les tests PROJECTION T02/T09/T10. La dépendance inverse introduirait un cycle Maven. Pour préserver les deux vraies racines, sans module ni outillage nouveau, T09/T10 résident dans `runtime-monolith`, package `com.kartaguez.pocoma`. Leurs noms de classe/méthode et les onze invocations sont conservés. Les sous-cas monolithe d'assemblage sont également attribués à T09/T10/T11 ; T02 porte la racine PROJECTION et son mode sans Worker. Une fixture locale au monolithe reprend les mécanismes de la fixture existante, sans dépendance entre classes de tests des runtimes. Il s'agit d'une allocation de preuve dans les ancres déjà déclarées, pas d'un franchissement de slice.

## AUD-I8-02 — Équivalence des gates

Deux appels `help:effective-pom` ont comparé C-I8 et F-L4, avec les mêmes propriétés de provenance et aucun filtre de tests. Les deux modèles contiennent exactement 61 projets et sont identiques octet par octet pour chaque élément XML `<project>` (ordre des projets sans effet sur ce constat). Les classes/méthodes sélectionnées proviennent des mêmes racines de source, avec les mêmes plugins, profils et propriétés. Cette comparaison ne constitue pas une exécution des tests.

C-I8 reste l'obligation de qualification canonique composée et Architecture ; F-L4 reste l'obligation de preuve globale à commande complète. Le même inventaire fournit les mêmes preuves, mais la suppression d'une invocation prescrite exige un arbitrage humain. Décision humaine explicite reçue dans cette mission : **« Mutualiser dans un full reactor unique »**. F-I8 reste ciblée ; un full reactor non filtré porte les obligations C-I8/G-I3…G-I8, Architecture et F-L4. Deux défauts concrets de composition ont ensuite rendu nécessaires des reactors correctifs (§ qualification) ; aucune campagne C-I8 séparée ni full supplémentaire par prudence.

Les observations de feedback ne satisfont pas les gates. Chaque échec, préflight et rejeu correctif conserve son reçu propre ; leur somme ne devient jamais un PASS global.

## Livraison applicative

Le code productif du dernier full exécuté est `a3470df662f4552d05785ab3ec191951c4cc9d56`. Le candidat `8a007768c23021153764473f6c6686042168df00` n’ajoute que les corrections de deux fichiers de tests Architecture ; aucun code productif, POM ou SQL ne diffère entre ces deux SHAs. Les commits sont `9661538511e7bfe255dfe7572cd8b1ebca6b9fc4` (implémentation), `a8933b7924567bf4e893f58dbc10312d2f3ccfc1` et `03c629d6af52474bda5e5ba56a4268264a8df024` (corrections d’oracles), puis `090b5327cd2a4e974bb8907c62c4e65b82a17e62` (isolation du scan legacy monolithe), puis `a3470df662f4552d05785ab3ec191951c4cc9d56` (restauration de l’ordre historique hors profil READ), puis `8a007768c23021153764473f6c6686042168df00` (alignement borné des seuls oracles Architecture). L'[inventaire applicatif](../testing/evidence/L4-I8/Application_Files.json) donne les 29 chemins et leurs SHA-256.

Le profil explicite `v3-read-worker` exige l'autorisation DEV, deux cibles physiques exactes, les quatre pools et leurs credentials externes, une sélection `read-participants`, les limites/candidatures correspondantes, la durée de lease et les limites de consultation. Aucun participant n'est activé par le seul nom du profil. Les noms sélectionnables sont exclusivement MaterializePotVersion, MaterializePotBalance, MaterializePotVisibility, MaterializeCurrentBinding, MaterializeCommandResult et MaterializeIdentityRequestResult. Les UUID, les sources, les checkpoints et les slots restent canoniques. Une sélection vide omet Worker/capacité/lifecycle ; les consultations disposent toujours de leur propriétaire de fermeture. La désactivation passe par drainage/redémarrage, sans reset durable.

PROJECTION assemble les READ sélectionnés. Le monolithe peut ajouter Command et IdentityRequest réels dans le même Worker et la même capacité. La factory `V3ReadParticipants` et les compositions lifecycle/discovery existantes restent les seules voies d'assemblage. Les modes fixture/probe, profils superposés, limites incohérentes, cibles incorrectes et droits métier READ excessifs/insuffisants sont refusés. Le préflight vérifie les neuf tables READ et leurs droits exacts par colonne, y compris les grants PUBLIC et les capacités sur des objets non alloués. Les permissions SQL et migrations existantes n'ont pas été élargies.

Les façades internes livrent `current`, `at`, `balance`, `expenses`, `visible`, `binding`, `commandResult` et `identityResult`. Pot courant capture V une fois ; le snapshot READ RR garde Binding, autorisation, composition, Shareholders et Balance à cette même V. Le manifeste Expense est complet et ordonné ; les détails sont chargés par lots exacts. Les vues Pot filtrent les User IDs et ensembles internes d’autorisation ; le propre Binding conserve le contrat du lecteur existant. Une dépendance absente ne provoque aucune substitution par une version voisine. L'ordre binding/droit à V/masquage puis minVersion/dépendances est conservé. Results reste indépendant du Binding courant. La navigation keyset ne confère aucun droit.

Les limites sont des entrées serveur obligatoires, sans nouveau plafond public ratifié. Une réponse dépassant le budget interne est indisponible explicitement, sans troncature ; les entrées malformées/hors bornes sont invalides. Deux consultations au maximum empruntent la réserve business READ via `Semaphore.tryAcquire`, sans utiliser control. L'activation et le snapshot Worker sont exposés séparément ; aucun watermark/readiness métier global n'est créé.

## Qualification locale et corrections

Le manifeste reste **11 méthodes ordinaires / 11 invocations**, sans ajout de scénario JUnit préventif. Les variantes sont des sous-cas nommés de ces méthodes. Deux nouvelles assertions d'Architecture vérifient les dépendances des lecteurs et les imports/profils des racines dans la gate.

Les [commandes de développement](../testing/evidence/L4-I8/Development_Commands.json) recensent une compilation et treize invocations de test ciblées. Leur worktree était dirty au HEAD documentaire initial ; elles sont du feedback, jamais une certification d'un SHA propre. Les erreurs et traces sont conservées. Les causes corrigées incluent la configuration de logs de la fixture, l'utilisation des métriques Hikari existantes, le propriétaire physique du checkpoint du double UNKNOWN, des emprunteurs WRITE artificiels qui consommaient la réserve Discovery, et la séparation des sous-cas UNKNOWN puisque le Worker draine après chacun. Les timeouts métier historiques et le protocole L2 n’ont pas été modifiés ; les attentes nouvelles sont bornées par leurs conditions et échéances déclarées.

Le feedback 14 a rencontré du bytecode Eclipse contenant `Unresolved compilation problems` dans les targets du worktree édité, après une compilation Maven sans erreur. Il n'est pas utilisé comme preuve de livraison. Les campagnes de qualification utilisent des checkouts neufs, sans targets hérités d'un éditeur, et des rapports produits directement dans des répertoires neufs avec ID d'invocation.

- **F-I8-01 : FAIL**, SHA `96615385…`, huit exécutions, une assertion T02 défaillante, trois méthodes du monolithe non exécutées. Le test essayait de refuser une configuration legacy qui était désactivée par son profil. La correction injecte une factory legacy réellement active.
- **F-I8-02 : FAIL**, SHA `a8933b79…`, onze exécutions, dix PASS, une ERROR T10. Après commit physique confirmé et perte d'ACK simulée, le Worker pouvait fermer le pool avant un rollback de nettoyage devenu inutile. La correction ne fait que retourner ce prêt déjà résolu ; elle ne masque pas une transaction incertaine.
- **Rejeu T10 : PASS**, SHA `03c629d6…`, une exécution fraîche, zéro failure/error/skip, **75,841759 s Maven**. Seule T10 était invalidée par cette correction ; les deux reçus F-I8 en échec restent inchangés. Le full non filtré réexécute ensuite les onze méthodes sur le SHA final.

- **Préflight FULL-01 : INCONCLUSIVE**, zéro test exécuté. Le collecteur refusait de tracer l’alias top-level `CurrentTransactionManagerPostgresTest`, déclaré dans `JpaTerminalSequencingPostgresTest.java`. Revue nominative de l’unique `@Test`, de sa source hachée et de son absence de provider/héritage ; ajout de cet alias au seul index en mémoire de l’invocation. Ni sélecteur, registre permanent, filtre Maven ni test applicatif modifié. Le [reçu de revue](../testing/evidence/L4-I8/Collector_Alias_Review.json) et le refus sont conservés.
- **Premier full réellement exécuté, FULL-02 : FAIL**, SHA `03c629d6…`, **1 619/0/3/0**, sur **2 191 attendus**, **1 921,200703 s Maven**. Les onze méthodes I8 sont PASS dans cette invocation, mais les trois invocations de `RuntimeMonolithPostgresTest` échouent et les 572 suivantes ne sont pas exécutées. Le module Architecture n’est pas atteint. La dépendance de test monolithe → runtime PROJECTION prescrite par le plan rendait sa main et sa configuration legacy visibles au scan historique du monolithe : cette seconde racine supprimait la configuration attendue du `transactionRunner` qualifié. Correction bornée : exclusion de la main PROJECTION et de ses classes imbriquées dans ce scan legacy ; aucun changement au TransactionRunner ou à son protocole.
- **Rejeu legacy : PASS**, SHA `090b5327…`, **3/0/0/0**, **16,443649 s Maven** ; les chemins ordinaires et les deux variantes booléennes retrouvent leur assemblage et la transaction primaire partagée. La gate obligatoire non filtrée restait non acquise ; conformément au §8.3 du plan, un **full correctif** au nouveau SHA est nécessaire. Il n’est ni un retry inchangé ni une seconde gate redondante : aucune campagne C-I8 autonome n’est ajoutée.

- **Deuxième full exécuté, FULL-03 : FAIL**, SHA `090b5327…`, **2 024/1/1/0**, 2 191 attendus, **4 101,876673 s Maven / 4 109,607982 s campagne** ; les 167 invocations Architecture ne sont pas atteintes. Les onze méthodes I8 et les trois cas legacy corrigés sont PASS ; `i5_r05` et `i5_r12` sont également PASS. Le tri alphabétique introduit dans la factory I8 inverse les probes Pot/Completion du profil historique. `V3WorkerPostgresTest#i5_w04` ligne836 compare Completion à2 au lieu de sa borne configurée3, car son helper associe 1/2/3 aux positions Command/Pot/Completion. `V3WorkerResourcesPostgresTest#i5_r06` ligne2662 sélectionne index1 avec un PotEvent et obtient une liste de claims vide puisque cette position est devenue Completion. Aucun dépassement réel des limites de `WorkerCapacity` n’est démontré. [Diagnostic et frontière](../testing/evidence/L4-I8/Historical_Order_Diagnosis.json). Le correctif `a3470df6…` restaure l’ordre antérieur uniquement hors READ ; branche READ inchangée, tests historiques intacts, aucun noyau L2 modifié. Rejeu ciblé des deux méthodes affectées, puis full corrigé nécessaire : la gate non filtrée et Architecture restent non acquises.

- **Rejeu ordre historique : PASS**, SHA `a3470df6…`, **2/0/0/0**, **54,753357 s Maven**. Seules les deux méthodes effectivement défaillantes sont sélectionnées ; le profil READ et les oracles existants restent inchangés. Le troisième full non filtré est ensuite lancé au même SHA propre pour acquérir la gate encore absente.

- **Troisième full exécuté, FULL-04 : FAIL**, SHA `a3470df6…`, **2 191/4/0/0**, **4 107,417690 s Maven**. Toutes les identités attendues sont présentes, dont les onze scénarios I8, les deux oracles d’ordre corrigés, r05/r12 et les 167 tests Architecture. Les **2 024 tests hors Architecture sont PASS**. Quatre assertions Architecture historiques ne tenaient pas compte des contrats autorisés I3–I8 : ports READ V3, interfaces exactes du participant L2, classe Legacy explicite de PROJECTION et exclusion READ ajoutée au profil Legacy Monolithe. [Diagnostic nominatif et limites](../testing/evidence/L4-I8/Architecture_Oracle_Diagnosis.json). Aucun défaut de code productif n’est établi par ces quatre findings ; les frontières d’infrastructure/WRITE/Engine restent interdites.
- **Rejeu complet du module Architecture : PASS**, SHA `8a007768…`, **167/0/0/0**, **47,827533 s Maven**. Les deux seuls fichiers modifiés sont des tests Architecture ; les 1 371 autres entrées du checkout qualifié sont identiques octet par octet au full. La [comparaison test-only](../testing/evidence/L4-I8/Architecture_Test_Only_Comparison.json) attribue cette équivalence bornée. Ce rejeu reste une campagne ciblée ; **aucun full PASS ni dispense n’est inféré sans arbitrage humain explicite**.

Les reçus exacts se trouvent sous [runs](../testing/evidence/L4-I8/runs/). La décision composite et les résultats définitifs figurent ci-dessous.

## Qualification composite autorisée

Décision humaine explicite : **« Autoriser la qualification composite et dispenser le quatrième full »**. Le [reçu](../testing/evidence/L4-I8/Human_Composite_Qualification_Decision.json) autorise la combinaison suivante, après correction des seuls oracles Architecture :

| Segment | SHA réellement testé | Tests / failures / errors / skips |
|---|---|---|
| Full complet, hors Architecture | `a3470df662f4552d05785ab3ec191951c4cc9d56` | **2 024/0/0/0** |
| Module Architecture complet, replay ciblé | `8a007768c23021153764473f6c6686042168df00` | **167/0/0/0** |

Les **2 191 identités distinctes** sont donc qualifiées par une preuve composite, avec correspondance exacte des identités, de l’environnement observé et des entrées productives. Ce n’est pas une exécution native 2 191/0/0/0 à un seul SHA. Le dernier full complet reste **FAIL — 2 191/4/0/0**, inchangé dans son archive. La campagne Architecture reste techniquement filtrée ; son utilisation comme contribution finale est couverte par la dispense humaine, sans substitution silencieuse. G-I3…G-I8 et Architecture différée/I8 sont satisfaites dans cette portée autorisée ; F-L4 est exécuté et son statut FAIL corrigé par oracles reste explicite. Aucune certification SPEC-10 globale ni clôture L4 n’en découle.

Le quatrième full avait été lancé pendant l’attente, faute de dispense reçue. Dès réception de l’arbitrage, son driver a reçu SIGTERM par le mécanisme d’interruption existant : **1 046 tests rapportés, 0/0/0**, campagne incomplète, **447,344756 s**. Son collecteur brut conserve FAIL/INCOMPLETE ; la disposition de livraison est **INTERRUPTED BY HUMAN DISPENSATION / INCONCLUSIVE pour une preuve full**. Ce run ne contribue pas à la qualification composite. Aucun résultat partiel n’est compté comme PASS de gate.

## Résultats T01–T11

Les onze méthodes sont observées exactement une fois, toutes PASS sans skip dans le full `a3470df6…` ; leurs variantes portent les mêmes sources productives dans le candidat `8a007768…`. Les durées ci-dessous sont des durées JUnit, pas des temps de campagne à sommer.

| Test | Propriété | Verdict | Durée JUnit |
|---|---|---|---:|
| T01 | Configuration et préflight temporel | PASS | 0.006 s |
| T02 | Assemblage et permissions | PASS | 7.261 s |
| T03 | Six progressions indépendantes | PASS | 10.659 s |
| T04 | Courant, histoire et capture V | PASS | 2.901 s |
| T05 | Retard et disponibilité exacte | PASS | 2.567 s |
| T06 | Manifestes et lots Expense | PASS | 2.625 s |
| T07 | Binding, droits et Results | PASS | 7.641 s |
| T08 | Navigation keyset non autorisante | PASS | 2.715 s |
| T09 | Drainage, crash et restart | PASS | 407.178 s |
| T10 | Ressources, renewals et UNKNOWN | PASS | 57.974 s |
| T11 | Coexistence des racines | PASS | 205.247 s |

Les [identités/artefacts](L4_I8_Proof_Manifest.json), la [matrice finale de 26 lignes SPEC-10](../testing/evidence/L4-I8/SPEC10_Final_Matrix.json) et la [comparaison test-only](../testing/evidence/L4-I8/Architecture_Test_Only_Comparison.json) attribuent chaque contribution à son SHA et son invocation. Les deux anciens scénarios `i5_r05`/`i5_r12` restent inclus et PASS dans le dernier full complet ; leurs réserves historiques ne sont pas levées.

## Commandes et coûts réels

Le [registre exhaustif](../testing/evidence/L4-I8/Commands_Actually_Executed.json) contient les arguments effectifs, identifiants, chemins de rapports et reçus. Les deux commandes constituant la qualification composite sont le full natif sans filtre et le replay de la totalité du module Architecture par liste positive de ses 28 classes. Leur graphe Maven est de 61 projets ; seuls les tests sélectionnés diffèrent. Les commandes C-I8 et F-L4 comparées avant lancement sont conservées dans les [modèles effectifs](../testing/evidence/L4-I8/Gate_Effective_Models.tar.gz).

| Campagne | Verdict brut | Tests/F/E/S | Maven ou phase interrompue (s) | Campagne (s) |
|---|---|---|---:|---:|
| F-I8-01-FAIL | FAIL | 8/1/0/0 | 45.013793 | 48.293415 |
| F-I8-02-FAIL | FAIL | 11/0/1/0 | 681.318614 | 686.187582 |
| T10-correction-PASS | PASS | 1/0/0/0 | 75.841759 | 79.816223 |
| FULL-preflight-01-INCONCLUSIVE | INCONCLUSIVE | 0/0/0/0 | NOT RUN | 2.366965 |
| FULL-02-FAIL | FAIL | 1619/0/3/0 | 1921.200703 | 1928.531404 |
| Legacy-isolation-correction-PASS | PASS | 3/0/0/0 | 16.443649 | 20.678350 |
| FULL-03-FAIL | FAIL | 2024/1/1/0 | 4101.876673 | 4109.607982 |
| Historical-order-correction-PASS | PASS | 2/0/0/0 | 54.753357 | 58.782089 |
| FULL-04-FAIL | FAIL | 2191/4/0/0 | 4107.417690 | 4115.192402 |
| Architecture-correction-PASS | PASS | 167/0/0/0 | 47.827533 | 51.655169 |
| FULL-05-INTERRUPTED-BY-DISPENSATION | FAIL | 1046/0/0/0 | 444.235888 | 447.344756 |

**Nombre réel : 23 processus Maven `test`** (13 feedback de développement et dix de qualification/correction), **dont quatre full démarrés : trois terminés FAIL et un interrompu par dispense**. Zéro campagne C-I8 autonome. Avec une compilation, treize lectures `help:effective-pom` et onze vérifications de version : **48 processus Maven**. Le préflight d’alias n’a lancé aucun test. Aucun full PASS n’est inventé, aucun `clean/install/-amd/-T` ni exclusion r05/r12 de confort.

Coût cumulé des onze reçus : **11548.456339 s, soit **192.474 min (3.208 h)** ; Maven test, phase interrompue comprise : **11495.929659 s**. Le plan prévoyait 110/155/230 min pour F-I8 + C-I8 + F-L4 avant mutualisation ; le coût observé est supérieur au centre de 155 min et inférieur à la haute indicative de 230 min. La comparaison n’est pas à périmètre constant : les échecs/corrections et le lancement interrompu sont inclus ici, le doublon canonique approuvé est supprimé. Les trois full complets coûtent respectivement 32,142 / 68,493 / 68,587 min de campagne. La phase idle historique de dix minutes est incluse à chaque full qui l’a atteinte. Aucun de ces chiffres n’est un plafond ou un SLO.

La durée globale de développement/édition/diagnostic/audit/publication n’a pas de reçu mural complet : **UNKNOWN**. Les totaux CLI du feedback sont conservés séparément. Compilation et collecte sont comprises dans les campagnes concernées ; les temps JUnit ne sont pas ajoutés au mural. Cache et image PostgreSQL chauds, machine locale ; aucune extrapolation de production.

## Ajustement borné des oracles Architecture

L’extension par rapport au §6 du plan touche uniquement `HexagonalArchitectureTest.java` et `V3ConsumptionFoundationArchitectureTest.java`, après quatre échecs concrets de la première Architecture effectivement atteinte. Les exceptions nouvelles sont des **types exacts, limités à leur package V3 d’origine**, pas des ouvertures de package : cinq ports READ/RecoveryReadContext et sept types d’API participant L2. ConsumptionEngine, WorkerCapacity, AttemptTransactions, infrastructure et runtime ne sont pas autorisés par ces exceptions. Une seule classe Legacy PROJECTION exacte est admise, en parallèle de l’exception Monolithe existante. La garde de profils est testée sémantiquement sur le défaut et toutes les combinaisons interdites ; les assertions SQL/migrations restent intactes. Aucun nouveau test JUnit, module Maven ou changement productif. L’audit indépendant vérifiera ces adaptations et leurs limites.

## AUD-I8-01 — Hypothèse de ressources

Les valeurs L2 et DEV historiques restent inchangées, notamment le défaut de lease 30 s. Le nouveau profil exige un dimensionnement explicite. Le préflight compte les candidats par autorité, les retours partiels progressants, une fenêtre d'omission bornée par binding, les polls et les marges. La configuration complète de qualification reste MW4/MC1, P4, batch32 puis batch1, lease180, claim60, intervalle10 et budget d'appel renewal5 secondes. Le calcul READ complet est **164,24 s < 180 s** ; 30 s est refusé. Ce calcul est une hypothèse, pas une certification de latence physique à sa borne pour toutes les charges possibles. Les mesures finales du full `a3470df6…` donnent T09 PROJECTION **180,788 s** pour 179,595 s restantes et Monolithe **180,506 s** pour 179,478 s restantes ; T11 **173,740 s** pour 172,804 s restantes. T10 renouvelle les 32 candidatures, avec huit omissions par variante et respectivement 32/8 retours partiels pour batch32/batch1. Le renewal UNKNOWN reste physiquement ouvert jusqu’au crash externe.

T10 distingue saturation contrôlée, omission physique SKIP LOCKED et retours partiels, ACK perdu après commit, transaction retenue avant commit, puis appel de renewal UNKNOWN dans une JVM enfant. Chaque candidat est confronté aux échéances PostgreSQL observées. Les réserves WRITE restent disponibles pour Discovery/observations ; les emprunteurs artificiels de la variante saine ne les consomment pas. La saturation READ combine deux consultations et les prêts représentant la borne des effets. Les appels UNKNOWN ne deviennent ni retry spéculatif ni faux STOPPED. Le cas renewal retenu se termine par CRASH externe, après témoin PID/XID et transaction PostgreSQL encore active.

T09 distingue préparation longue renouvelée, transaction finale retenue, drainage normal, puis crash réel d'une JVM exécutant chaque vraie racine et restart sans reset. Les incarnations changent après les échéances durables ; les quarantaines et l'historique restent présents. Le coût de takeover de la lease180 est mesuré, sans promesse de reprise en 30 s. T11 vérifie le chevauchement puis les candidatures disjointes, la destination canonique commune et les 64 connexions de deux instances.

L’[observation SQL externe pendant T11](../testing/evidence/L4-I8/Server_Capacity_Observation.json) porte le SHA `a3470df6…` et l’ID du dernier full complet. PostgreSQL17.11 : max_connections=100, réserves superuser=3, autres réserves=0. Les huit pools ont **64 connexions physiques**, business6/control10 par autorité et racine ; 66 clients sont présents au témoin. Les **97 places ordinaires couvrent le budget déclaré 64 + 12 = 76**. Le budget fixture/admin/observateur de12 n’est pas une charge maximale mesurée. Observation SQL en lecture seule, sans Maven ni test supplémentaire. Les observations antérieures et la non-observation du full interrompu gardent leur propre attribution.

**Réserve maintenue :** lease180 est qualifiée dans les variantes et l'environnement observés ; aucun dimensionnement de production n'est accepté. Les omissions arbitrairement répétées, transports non résolus et charges hors hypothèse restent soumis à la suspension/drainage conservateurs L2. Aucun nouveau protocole de renouvellement, recovery, transfert de ticket ou fermeture spéculative n'a été introduit. Les [mesures](../testing/evidence/L4-I8/Resource_Observations.json) et [témoins physiques](../testing/evidence/L4-I8/physical-final/) proviennent du full complet `a3470df6…` ; les copies sont identiques aux membres de son archive.

## AUD-I8-03 — Diagnostics et oracles

T03 identifie le participant retenu et chaque branche qui progresse ; elle compare les effets et la suppression du slot au même PID/XID, y compris les trois publications atomiques PotVersion/SubobjectVersion/CurrentPot. T07 distingue EUI/issuer, token propre/étranger, créateur sans Shareholder, détachement d'un puis des deux Shareholders, droit historique, résultats requester-only et fenêtre réelle d'Unlink retenue puis propagée. T10 identifie batch, consommation/candidats, omissions/retours, phase UNKNOWN et témoins physiques. Les barrières et attentes portent sur des conditions métier/SQL ; aucune assertion n'est rejouée pour fabriquer un PASS.

Les onze scénarios ne recertifient pas six fois L2. Les contributions historiques sont attribuées à leurs SHA, et les nouvelles hypothèses d'assemblage/consultation/ressources ont leurs propres preuves. L'[analyse de réutilisation](../testing/evidence/L4-I8/Historical_Reuse_Comparison.json) vérifie 317 fichiers d'archives sans écart, les 1 357 entrées historiques recovery et l'absence de diff des chemins productifs protégés. Elle ne prétend aucune équivalence globale d'environnement, de graphe Maven ou de charge.

## Surveillance de la campagne longue

Un instantané [processus/threads](../testing/evidence/L4-I8/health/Full03_Health.json) et [attentes PostgreSQL](../testing/evidence/L4-I8/health/Full03_Postgres_Health.json) a été conservé pendant la suite historique COMMAND. La JVM était active dans `i5_w14`, en attente de reprise métier après SIGKILL et échéance durable ; les deux fixtures observées n’avaient aucune session bloquée par verrou. Aucun arrêt, timeout relevé ou relancement n’en a découlé. Le [témoin propre au full final](../testing/evidence/L4-I8/health/Full04_Health.json), attribué au SHA `a3470df6…` et à son ID, observe ensuite `i5_w16` : deux fenêtres idle de cinq minutes sont imposées par ce test historique. Les témoins Full03 conservent leur ancien SHA et leur ancienne invocation. Ces observations ne constituent pas un benchmark global.

## Limites et gouvernance

La qualification interne utilise des EUI fournies par des appelants de confiance et des admissions internes contrôlées, pas une authentification OIDC certifiée. Les grands manifestes administrativement installés dans READ prouvent le nouveau lecteur ; la chaîne réelle WRITE → Discovery/Consumption → READ est démontrée séparément. Les deux autorités sont des bases PostgreSQL distinctes sur le serveur de fixture, pas une preuve de résilience de deux hébergements de production.

Restent à L5 : HTTP/OIDC, consentement, sessions, enveloppes/codes/polling, curseurs et plafonds publics, CurrentInvitation. Restent à L6 : reconstruction administrative complète, reset/rejeu, profils exhaustifs de charge, stockage/WAL/histoire longue et performance/SLO globaux. La convergence physique et rationalisation Maven restent L7. Les réserves I1, I3–I7/recovery et de disponibilité L2/L3 sont conservées ; I2-QUAL-01 reste résolu.

**L4 reste ouvert.** L'audit indépendant du code et des preuves, puis la décision humaine de clôture L4, interviennent après livraison. Une gate Maven globale réussie ne constitue pas automatiquement une certification globale SPEC-10 ou une décision de clôture.
