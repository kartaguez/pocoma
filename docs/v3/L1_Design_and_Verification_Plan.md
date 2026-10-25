# V3-6 — L1-A : conception et plan de vérification du noyau Pot

**Statut : conception détaillée PROPOSÉE, en attente de revue contradictoire ; L1-A NON IMPLÉMENTÉ.** Date : 2026-10-25. Référence inspectée : `5cc5db870c9cde91fd86997b365a61705dc95cf3`, branche de publication `feat/v3-architecture`.

Ce plan technique est subordonné aux SPEC ; il ne constitue ni un nouveau canon, ni une autorisation de code, ni une preuve fonctionnelle L1. Le [canon, §11](V3_Canon_and_Roadmap.md#11-v3-6--passage-vers-l1) reste l'autorité de progression. L0 demeure clôturé documentairement et accepté pour la poursuite, avec I-01, A-01 et A-02 ouvertes selon le [rapport L0](L0_Execution_Report.md#independent-review-and-documentary-closure--2026-10-25). Aucun PASS L0 ou V2 n'est transféré à L1.

## 1. Sources, portée et niveaux de décision

Sources lues dans le dépôt : [SPEC-01](SPEC-01_Pot_Domain.md) (modèle et règles métier), [SPEC-03](SPEC-03_Events_Requests_Consumables.md) (mutation et complétion), [SPEC-10](SPEC-10_Test_Certification.md) (oracles et certification), [inventaire L0](L0_Technical_Inventory.md), [rapport L0](L0_Execution_Report.md), [canon](V3_Canon_and_Roadmap.md) et [politique du réacteur](../testing/Reactor_Verification_Policy.md). Les observations de code ci-dessous proviennent de la référence inspectée ; la reprise des travaux doit revalider les écarts si cette référence évolue. Aucune conversation externe n'est nécessaire pour appliquer ce plan.

**Acquis :** domaine Java pur indépendant de Spring, JPA, SQL et des transactions ; composantes immuables ; invariants protégés dans le domaine ; injection explicite par constructeur aux frontières applicatives ; Pot unité logique de cohérence et de version globale ; chargement sélectif ; réutilisation sélective V2 sans compatibilité artificielle. Les règles fonctionnelles de SPEC-01 ne sont pas rouvertes.

**Proposé :** structures Java, emplacement des transitions, forme des faits et résultats, séquence de réalisation et matrice unitaire de ce document. **À prouver :** tous les comportements L1, pureté effective des nouveaux composants, clôture A-01 et intégration ultérieure. Un exemple de nom Java n'est jamais une signature validée.

Objectif L1-A : rendre exprimables et testables les huit composantes, leurs versions indépendantes, leurs transitions pures, les préconditions métier et l'atomicité logique d'une mutation. Les trois opérations du §8 sont les scénarios de conception obligatoires ; créations et suppressions complètent les invariants du noyau. Les droits métier nécessaires sont contrôlés à partir de faits explicites, sans simuler une authentification réussie.

Exclusions : PostgreSQL, DDL et migrations de production, repositories/adaptateurs, CAS et verrous réels, transaction, historique durable, requests et résultats durables, publication d'événements, Consumption, READ, endpoints, Bruno, k6, activation. La résolution sécurisée d'EUI/bindingToken et les invitations ne sont pas implémentées ici. Le calcul complet et la matérialisation des balances restent aux lots suivants ; L1-A protège les valeurs et références financières locales et prépare leurs oracles.

## 2. Cartographie V2 ciblée — suivi I-01

Les liens mènent aux sources existantes, non à des livrables V3. REUSE/ADAPT/REPLACE/DEFER sont des orientations proposées ; aucun fichier Java n'est modifié par ce mandat.

| Sources inspectées | Observation V2 | Orientation L1 et propriété à préserver |
|---|---|---|
| [IDs](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/value/id/), [UserId](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/value/UserId.java) | UUID typés, égalité distinguant les classes d'identifiant | REUSE des identités stables ; préciser le nom Java du PocomaUserId sans introduire de nouveau domaine Identity |
| [Label](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/value/Label.java), [Name](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/value/Name.java), [Amount](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/value/Amount.java) | Valeurs immuables ; Amount refuse le négatif | REUSE après contrôle des invariants applicables et de l'égalité pour les no-op |
| [Weight](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/value/Weight.java), [Fraction](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/value/Fraction.java) | Weight accepte zéro ; Fraction normalise avec BigInteger intermédiaire puis `longValueExact()` | ADAPT : poids strictement positif, exactitude sans arrondi ; capacité numérique à décider au §13, sans inventer de plafond fonctionnel |
| [PotHeader](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/aggregate/PotHeader.java) | Mutable ; mélange identité/deleted et label/creatorId | REPLACE du regroupement par PotHeader/PotDetails immuables ; créateur conservé |
| [Shareholder](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/entity/Shareholder.java), [PotShareholders](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/aggregate/PotShareholders.java) | Entité immuable mais réunissant Header/Details/Weight ; collection mutable et suivi des IDs touchés même sans changement de valeur | ADAPT/REPLACE : versions par composante ; ensemble des changements effectifs ; ne pas reversionner le groupe entier |
| [ExpenseHeader](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/aggregate/ExpenseHeader.java) | Mutable ; mélange deleted, détails, montant et payeur | REPLACE par Header/Details/Financials ; une transition financière couvre montant, payeur et participants ensemble |
| [ExpenseShares](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/aggregate/ExpenseShares.java), [ExpenseShare](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/association/ExpenseShare.java) | Parts à poids obligatoire ; fusion des doublons par somme ; pas de défaut optionnel V3 | REPLACE/ADAPT vers participants identifiés et specificWeight facultatif ; ne pas hériter silencieusement de la fusion V2 |
| [PotGlobalVersion](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/version/PotGlobalVersion.java), [PotVersionMetadata](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/version/PotVersionMetadata.java), [factories](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/factory/) | Version globale positive ; métadonnée horodatée ; génération UUID interne | ADAPT : distinguer version de travail/candidate et révisions locales ; fournir explicitement les IDs pour rendre la transition déterministe ; ne pas attribuer d'heure de commit dans le domaine |
| [PotBusinessAuthorizationPolicy](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/authorization/PotBusinessAuthorizationPolicy.java) | Politique pure ; V2 réserve encore plusieurs modifications au créateur | ADAPT au §5 de SPEC-01 ; noyau des préconditions en L1-A, matrice détaillée et intégration des droits en L1-D/L1-C |
| [usecase](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/usecase/), [UpdatePotDetailsService](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/UpdatePotDetailsService.java), [UpdatePotShareholdersWeightsService](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/UpdatePotShareholdersWeightsService.java), [UpdateExpenseSharesService](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/UpdateExpenseSharesService.java) | Dix interfaces existantes ; services chargeant, autorisant, mutant, incrémentant V, persistant et publiant ; incrément sans détection de no-op dans ces trois services | DEFER de l'adaptation applicative à L1-C ; extraire conceptuellement les invariants purs. UpdateExpenseShares n'est pas déjà le contrat UpdateExpenseFinancials |
| [context](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/context/), [ports de persistance](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/port/persistence/) | Témoins temporels de Header, Shareholder groupé et parts ; contrôles de version observée déjà présents | ADAPT ultérieurement aux dépendances par composante ; ne pas copier Context/Input/Snapshot/Port pour chaque nouveau type |
| [PotBalancesCalculator](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/projection/balance/PotBalancesCalculator.java) | Calcul exact par Expense réutilisable ; API différentielle depuis balance précédente | DEFER L1-D/READ ; retenir l'arithmétique utile, pas la dépendance à V−1 ni les poids obligatoires des parts V2 |
| [tests domain-pot](../../app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/), [tests engine-write-pot](../../app/engine-write-pot/src/test/java/com/kartaguez/pocoma/engine/write/pot/), [V3FoundationArchitectureTest](../../app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/V3FoundationArchitectureTest.java) | Tests de valeurs/agrégats/contextes ; garde pureté et direction L0 | Réutiliser les intentions d'oracles, remplacer les regroupements/attentes V2 ; couverture ports internes à compléter (§11) ; aucun PASS transféré |

Cette analyse complète I-01 pour le noyau concerné ; elle ne clôt pas l'inventaire des futurs writers, mappings SQL ou projections. La rupture des API V2 n'est pas un objectif de L1-A : si leur remplacement exige un autre module, analyser et redéclarer l'impact avant toute modification (§12), sans adaptateur de compatibilité ajouté par principe.

## 3. Huit composantes immuables, identités et versions

| Composante normative | Identité métier portée | Valeurs et invariants | Révision locale |
|---|---|---|---|
| PotHeader | PotId | deleted, exclusivement dans le Header | h Pot |
| PotDetails | PotId | label ; creatorId obligatoire et immuable entre transitions | d Pot |
| ShareholderHeader | ShareholderId, rattachement Pot | deleted | h de ce Shareholder |
| ShareholderDetails | même ShareholderId | name ; userId facultatif et historisé, sans unicité par User dans le Pot | d de ce Shareholder |
| ShareholderWeight | même ShareholderId | defaultWeight strictement positif | w de ce Shareholder |
| ExpenseHeader | ExpenseId, rattachement Pot | deleted | h de cette Expense |
| ExpenseDetails | même ExpenseId | label, date | d de cette Expense |
| ExpenseFinancials | même ExpenseId | amount ≥ 0 ; payerId ; participants non vides, specificWeight facultatif et strictement positif si présent | f de cette Expense |

Chaque composante enfant porte directement l'identité stable de l'enfant ; l'appartenance au Pot doit être vérifiable sans identité de ligne SQL. Répéter PotId dans toutes les valeurs ou le porter dans une composition contrôlée reste un choix de représentation (§4), avec obligation de refuser toute composition entre Pots ou enfants différents. Une référence financière porte ShareholderId, jamais UserId, version locale ou ID technique historique. Le payeur peut ne pas être participant. Le créateur peut ne correspondre à aucun Shareholder ; aucun Membership supplémentaire.

Création : toutes les composantes du nouvel objet naissent ensemble en révision locale 1, à la même V globale (Pot initial V1). Avant création, l'objet n'a aucune composante applicable. Une révision locale ne mesure ni l'âge du Pot ni la dernière V globale ; elle avance de 1 seulement si sa valeur métier change. Une mutation multi-composantes ne produit qu'une seule V candidate.

Immutabilité transitive : aucun setter, collection mutable conservée ou référence vers un agrégat mutable V2. Copier défensivement les collections entrantes et exposées ; leurs éléments sont eux-mêmes immuables. L'égalité porte sur les valeurs métier normalisées et l'identité, sans horodatage ni ID de ligne ; une permutation des participants identifiés ne crée pas d'effet métier. La représentation des doublons doit être explicite, jamais leur fusion implicite V2.

Les bornes `activeFrom/activeTo` sont exprimées en V globales, jamais en révisions locales. L1-A a besoin du fait « depuis quelle V cette valeur cible est-elle applicable ? » pour l'obsolescence ; il n'a pas besoin de fermer une ligne persistée. L1-B sera responsable de `[activeFrom,activeTo)` et de l'unicité de l'instance applicable, sans muter un objet historique déjà remis au domaine.

## 4. Structures Java à évaluer, sans signatures figées

| Concept étudié | Invariant qui pourrait le justifier | Coût / alternative / proposition à revoir |
|---|---|---|
| `PotState` | Composition cohérente d'un Pot à une V, références et versions homogènes | Utile pour composition pure et oracle complet ; ne pas en faire un agrégat intégral obligatoire à chaque mutation. Ne pas représenter « non chargé » par collection vide |
| `ShareholderState` | Même ShareholderId/Pot pour Header, Details et Weight | À retenir seulement si la construction contrôlée supprime un risque concret de mélange ; sinon composants nécessaires passés directement |
| `ExpenseState` | Même ExpenseId/Pot et cohérence Header/Details/Financials | Même examen ; ne pas imposer Details à une modification Financials sans dépendance métier |
| `Versioned<T>` | Associer valeur immuable, révision locale positive et début d'applicabilité global cohérent | Peut éviter huit copies de métadonnées ; ne doit pas accepter T mutable, versions de Pots mélangées ou métadonnées SQL arbitraires. Comparer avec métadonnées propres aux composantes ; encapsuler construction et incrément |
| `TransitionResult` ou résultats spécialisés | Distinguer effet, succès sans effet et rejet sans combinaison impossible | Petite somme typée possible ; préférer résultats spécialisés si leurs invariants diffèrent. Éviter registre de transitions, moteur générique, Map non typée, booléens contradictoires et résultat universel nullable |

Aucun de ces noms n'est validé. Le critère de choix est de rendre impossibles les incohérences du §5, avec le moins de concepts possible. Pas de nouvelle classe Context/Input/Snapshot/Result/Service/Port sans invariant ou frontière démontré. Les valeurs intrinsèquement invalides sont refusées à la construction ; les rejets d'une intention métier valide sont distingués des anomalies techniques. La nomenclature exacte des codes et exceptions reste à décider, sans imposer de framework.

## 5. Pot cohérent et chargement sélectif

Le Pot est la frontière de cohérence logique et de versionnement global ; il n'est pas une obligation de charger tous ses enfants. Une transition reçoit seulement les composantes ciblées et les faits nécessaires à ses invariants. Aucune lecture paresseuse, repository, callback SQL ou transaction cachée dans le domaine.

| Transition | Composantes et faits minimaux envisagés | Données sans dépendance directe |
|---|---|---|
| UpdatePotDetails | PotId, V de travail, PotHeader actif, PotDetails et son début d'applicabilité, observation et faits de droits courants | Expenses et poids ; seuls les faits de rattachement nécessaires à l'autorisation sont requis |
| UpdateShareholderWeights | Pot actif, V de travail, Header actif et Weight versionné de chaque cible, identité/appartenance, observation et droits courants | Financials des Expenses ; Details des cibles hors faits de droits réellement utiles |
| UpdateExpenseFinancials | Pot/Expense Headers actifs, Financials cible versionnés, faits d'existence/appartenance/activité des Shareholders référencés, existence à l'observation et droits courants | Révisions des noms/rattachements et des poids par défaut des références comme critères d'obsolescence |
| DeleteShareholder | Pot et Shareholder actifs, qualité de créateur, fait exhaustif d'absence de référence dans toute Expense active | Contenu intégral des Expenses si un fait autoritatif exhaustif suffit |

Les faits portent leur sujet (Pot/objet), leur V de travail et les témoins utiles. La transition refuse une entrée mélangeant identités, Pots ou V de travail, un témoin temporel hors de `1..V_travail`, une cible demandée sans ses faits, ou des clés incohérentes entre collections. Une liste partielle n'est jamais une preuve d'absence de référence. Un fait incohérent ou obligatoire manquant après existence établie est une anomalie technique, pas un no-op ni un rejet fonctionnel inventé.

La transition peut vérifier ces cohérences internes ; elle ne peut pas prouver qu'un appelant a lu des faits vrais dans une seule vue durable. L1-B fournit la lecture autoritative, L1-C garantit leur cohérence jusqu'au commit ou reprend entièrement. La validation d'identité/binding produit des faits applicatifs fiables, pas un booléen d'autorisation librement fourni par le client. Les droits métier restent calculés selon SPEC-01 à l'état courant : créateur ou utilisateur rattaché à un Shareholder actif, avec réserves sur suppressions et rattachement.

## 6. Préconditions, obsolescence et ordre de transition

`observedVersion` est l'observation globale initiale, conservée entre tentatives ; `V_travail` est l'état courant retenu pour la tentative. Une inégalité entre ces versions ne rend pas automatiquement la commande obsolète. Vérifier la plage d'observation et l'existence à l'observation des cibles requises. Pour une composante existant déjà à l'observation, `activeFrom_courant <= observedVersion` établit qu'elle n'a pas changé depuis ; le seul numéro local ne remplace pas ce témoin global. Un aller-retour de valeur A→B→A reste un changement et ne contourne pas l'obsolescence.

Ordre logique proposé :

1. Vérifier les valeurs et la cohérence des faits fournis ; identifier toutes les cibles.
2. Contrôler existence, appartenance, Headers actifs et droits courants ; vérifier les dépendances temporelles de toutes les cibles et références requises.
3. Valider l'état métier demandé dans son ensemble (références, montants, poids, suppressions).
4. Après ces contrôles, comparer les valeurs métier et calculer l'ensemble exact des composantes effectivement modifiées.
5. Produire un résultat complet : rejet sans état modifié, succès sans effet, ou proposition effective cohérente et unique.

Cet ordre impose l'obsolescence avant reconnaissance du no-op ; il ne fixe pas une priorité publique de codes d'erreur entre plusieurs refus simultanés. Une cible demandée égale à sa valeur actuelle doit tout de même satisfaire ses préconditions. Le contrôle de Header actif est distinct de celui de non-obsolescence : un Details inchangé ne permet pas de modifier son objet supprimé.

| Intention | Dépendances temporelles bloquantes | Effet possible |
|---|---|---|
| Modifier PotDetails | PotDetails | PotDetails seul |
| Modifier ShareholderDetails / Weight | Details / Weight de chaque cible, respectivement | Seulement les valeurs ciblées différentes |
| Modifier ExpenseDetails / Financials | Details / Financials de l'Expense, respectivement | Composante ciblée seule |
| Créer enfant | Pas de composante cible antérieure ; Pot actif et références utiles valides | Toutes ses composantes à révision 1 |
| Supprimer Expense | Header + Details + Financials de cette Expense | Header seul ; Weight d'un Shareholder n'est pas une dépendance d'obsolescence |
| Supprimer Pot | `observedVersion == V_travail` | PotHeader seul, opération terminale |
| Supprimer Shareholder | Créateur, activité et aucune référence d'Expense active ; pas de dépendance supplémentaire inventée | ShareholderHeader seul ; N-01 résolu documentairement au §13, D01-M et D02-Q/R/S |

Pour toute création/modification de Financials, le Header de chaque Shareholder référencé doit exister à observedVersion et être encore actif à l'exécution ; contrôler son appartenance au Pot. Le fait d'existence à l'observation ne doit pas être confondu avec la fraîcheur de ses Details ou Weight. Le cas d'un Header supprimé est refusé par activité actuelle ; les détails de sélection temporelle réelle relèvent de L1-B.

## 7. Résultat typé, effet et version candidate

La forme Java reste proposée ; le contrat attendu des trois issues est précis :

| Issue | État / ensemble de changements | Version globale |
|---|---|---|
| Rejet métier | Motif typé ; aucun nouvel état applicable ni changement partiel | Aucune candidate ; version de travail inchangée |
| Succès sans effet | Valeurs inchangées ; ensemble de changements vide ; préconditions toutes satisfaites | Aucune nouvelle candidate ; L1-C retourne `resultingPotVersion = V_travail` |
| Mutation effective | Nouvelles valeurs immuables cohérentes et références/révisions locales des seules composantes changées | Une seule candidate `V_travail + 1`, portée par l'issue globale |

Une mutation multi-cibles valide d'abord toutes ses cibles puis construit son résultat complet ; aucune mise à jour en place ni résultat partiel observable. Le résultat inclut les composantes nécessaires pour appliquer la mutation sans reconstruire artificiellement le Pot entier ; son ensemble des changements doit correspondre exactement aux différences de valeurs et de révisions. Les inchangées conservent leur révision et peuvent partager les mêmes instances immuables ; aucune nouvelle version historique ne leur est attribuée.

**Candidate ne signifie pas durable.** Le domaine ne réserve pas V+1, n'effectue aucun CAS et ne déclare aucun commit. Deux transitions préparées sur la même V peuvent proposer la même candidate ; une seule sera validée durablement si elles entrent en conflit. L1-C jette la proposition perdante, recharge les faits à une nouvelle V de travail et réévalue avec l'observation initiale. Un conflit CAS est technique, pas un rejet métier automatique. Aucun compteur, boucle de retry ou horloge partagée dans la transition pure.

L1-B fermera les anciennes instances modifiées à la V effectivement validée et ouvrira les nouvelles à cette V. L1-C coordonnera transaction et publication : SPEC-03 exige un seul POT_EVENT par mutation effective (même multi-cibles), avec changedComponents ; aucun pour no-op ou rejet, mais résultat terminal et complétion. L1-A fournit des changements exploitables, sans construire une enveloppe événementielle universelle ni publier un événement prématuré.

## 8. Trois scénarios de conception obligatoires

Les chiffres suivants sont des fixtures proposées, pas des résultats exécutés. Chaque variante commence depuis sa fixture explicitement définie ; h/d/w/f sont des révisions locales.

### UpdatePotDetails

À V_travail=8, Pot actif, Header h1, Details d2 applicable depuis V5, label A et créateur C. L'auteur observe V6 et possède actuellement les droits de mutation.

| Variante | Oracle attendu |
|---|---|
| Demande label B | PotDetails d3 seul, C strictement conservé, candidate V9 ; PotHeader h1 et enfants inchangés |
| Demande label A | Succès sans effet après contrôle des droits, activité et observation ; d2, V8, aucun changement |
| Details d2 applicable depuis V7 avec observation V6, demande A déjà présente | Rejet obsolète, même si égalité ; aucune candidate |
| Header supprimé, auteur non autorisé, observation future ou faits d'un autre Pot | Refus ou anomalie selon nature ; aucun résultat effectif |
| Tentative de changer creatorId | L'opération de changement de label ne l'accepte pas ; la construction du nouvel état conserve le créateur, sans setter ni chemin de remplacement arbitraire |

La simple immutabilité d'une valeur PotDetails ne suffit pas : la transition doit empêcher de substituer un autre créateur entre deux états.

### UpdateShareholderWeights

À V_travail=12, observation V10, Pot actif, cibles A/B du même Pot actives : A w2=1 applicable depuis V7 ; B w3=2 depuis V9. Demande A=3, B=2. Droits courants valides.

| Variante | Oracle attendu |
|---|---|
| Lot valide | A passe à w3 ; B reste w3 ; une seule candidate V13 ; aucun Header/Details ni ExpenseFinancials changé |
| B w3 applicable depuis V11 | Rejet global obsolète, bien que B soit demandé à valeur égale ; A reste w2, aucune candidate |
| A et B demandés à valeur inchangée, toutes préconditions valides | Succès sans effet, V12 et révisions conservées |
| Dernière cible absente, supprimée, hors Pot ou poids zéro/négatif | Refus complet ; même la première cible valide ne change pas |
| Deux poids effectivement différents | Deux révisions locales +1, une seule candidate V13 |

L'absence d'effet sur ExpenseFinancials n'implique pas l'absence d'effet sur les futurs soldes à V13 : les participations sans specificWeight utiliseront les nouveaux défauts. Aucun chargement des Financials de toutes les Expenses n'est requis pour cette transition.

### UpdateExpenseFinancials

À V_travail=20, observation V16, Pot et Expense E actifs. E.f3 est applicable depuis V14 ; les Headers des Shareholders A/B/C existent depuis V2/V4/V8, appartiennent au Pot et sont encore actifs. Demande : amount=100, payerId=A (non participant), participants B sans poids spécifique et C avec specificWeight=2.

| Variante | Oracle attendu |
|---|---|
| Financials demandés différents, références valides | E.f4 seul ; cohérence montant/payeur/participants ; candidate V21 ; E.Header/Details et Shareholders inchangés |
| Nom ou userId de B modifié à V18 ; Weight de B modifié à V19 | Ces changements seuls ne rendent pas Financials obsolètes. Réévaluer séparément les droits courants de l'auteur ; si toujours autorisé, accepter |
| Un Shareholder référencé créé à V17 > observation V16 | Refus : référence non valide à l'observation, même si active aujourd'hui |
| Header de C supprimé, référence absente/hors Pot, Pot ou Expense supprimé | Refus sans état partiel ; ne pas résoudre par UserId ni révision technique |
| E.f4 courant applicable depuis V18 > V16, demande égale au contenu courant | Rejet obsolète avant no-op ; aucun état candidat |
| Montant négatif, participants vides ou specificWeight zéro/négatif | Invariant financier refusé ; aucune candidate |
| Valeurs financières identiques après normalisation et toutes préconditions valides | Succès sans effet ; f3, V20 conservées |

Les poids effectifs ne sont pas figés dans Financials : pour un calcul à V, utiliser specificWeight sinon defaultWeight applicable à V. L1-A prouve l'intégrité des références et valeurs du résultat ; résolution historique PostgreSQL et balance exacte sur l'état composé sont dues ensuite. Si la transition n'a pas besoin du defaultWeight, elle ne le charge pas pour fabriquer un contrôle d'obsolescence.

## 9. Frontières avec les autres lots

| Lot | Responsabilité et contrat transmis |
|---|---|
| L1-A | Valeurs/composantes immuables, construction cohérente, préconditions pures, rejets/no-op/multi-cibles, candidate et changements typés ; aucune durabilité prétendue |
| L1-B | Schéma V3, lignes historiques et identités techniques, sélection à V, absence de trous/chevauchements, reconstruction exacte sans V−1 et accès groupés ; fournit les faits autoritatifs nécessaires |
| L1-C | Assemblage par constructeur, identité/binding fiables, chargement cohérent, maintien des préconditions jusqu'au commit, CAS global, rollback/retry complet avec observedVersion inchangé, mutations et complétions atomiques ; transforme la candidate en version durable |
| L1-D | Matrice détaillée des droits, rattachement/détachement, références financières, résolution des poids et calcul rationnel complet ; prolonge les invariants locaux déjà protégés en L1-A |
| L1-E et lots événements/Consumption/READ suivants | Intégration et preuves conjointes SPEC-10, publication, matérialisations et HTTP ; aucune garantie physique déduite des seuls tests purs |

Le chevauchement des sujets est intentionnel : L1-A ne peut produire des Financials invalides en attendant L1-D ; L1-C ne doit pas recopier un second algorithme métier divergent. Les droits de suppressions/références sont déjà des préconditions pures à modéliser ; leurs faits exhaustifs et leur protection sous concurrence restent à prouver avec la persistance et l'exécution.

## 10. Matrice JUnit proposée et critères probatoires

Toutes les lignes ci-dessous sont **À IMPLÉMENTER / NON EXÉCUTÉES**. Les identifiants `L1A-*` sont des repères de réalisation, pas de nouvelles exigences normatives. Les liens DOM renvoient aux protocoles [SPEC-10, §3](SPEC-10_Test_Certification.md#3-domaine). Les preuves unitaires sont des contributions bornées, jamais un PASS global DOM-01/02/03.

| Repère | Cas à écrire / oracle décisif indépendant | Couverture normative / limite |
|---|---|---|
| L1A-01 | Modifier les collections sources après construction, tenter de modifier les collections retournées, réutiliser l'état initial après transition : valeurs initiales intactes, aucune mutation transitive | Immutabilité, base DOM-01 ; pas de preuve SQL |
| L1A-02 | Créer Pot puis enfants : toutes leurs composantes locales à 1 ; modifier successivement chaque type : seule sa révision avance ; identités stables conservées | D01-A/B/C/D/E, partie pure |
| L1A-03 | Mutation simple et multi-cibles : candidate = V_travail+1 une seule fois ; ensemble des changements exactement égal aux différences, anciennes valeurs intactes | D01-K, D02-H ; commit/événement exclus |
| L1A-04 | Demande égale valide puis demande égale obsolète (y compris retour A→B→A) : no-op seulement dans le premier cas, aucune candidate dans les deux | D02-I/J ; complétions dues ensuite |
| L1A-05 | Lot mixte valeurs modifiées/inchangées ; rendre la dernière cible obsolète/invalide ; permuter l'ordre des cibles : succès cohérent ou rejet global sans effet partiel | D02-G/H ; pas de preuve transactionnelle |
| L1A-06 | Exécuter toutes les variantes UpdatePotDetails du §8 ; créateur non-Shareholder conservé, aucun moyen de le remplacer | DOM-01/02 et SPEC-01 §§2/5/6 |
| L1A-07 | Toutes variantes UpdateShareholderWeights ; aucune révision d'ExpenseFinancials ; un changement de Details ne bloque pas le Weight compatible | D01-D, D02-B/D/G/H ; soldes ultérieurs dus L1-D |
| L1A-08 | Toutes variantes UpdateExpenseFinancials ; IDs stables, bon Pot, Headers actifs, référence antérieure à l'observation, indépendance Details/Weight, auteur encore autorisé | D01-H (composition), D02-D/N, D03-K ; historique/identité sécurisée non prouvés |
| L1A-09 | DeleteExpense : Header seul révisé, refus si Details/Financials ont changé ; Weight indépendant accepté. DeletePot : égalité globale exigée, Header seul et terminalité. DeleteShareholder : rejouer V1..V4 de D01-M en mémoire ; à V4 seul Header h1→h2 deleted, Details d1/Weight w2 inchangés, une seule candidate ; états V2/V3 conservés et mutations ultérieures refusées | D01-F/M (partie pure), D02-C/E/N ; intervalles/histoire durables L1-B/C et HTTP A11 exclus |
| L1A-10 | DeleteShareholder : Pot et Shareholder actifs requis ; refuser le participant non créateur sans candidate ni révision ; tester séparément payeur seul et participant seul d'Expense active ; autoriser le créateur si seules des Expenses supprimées référencent la cible ; aucune altération des Expenses ni de leurs Financials historiques fournis | SPEC-01 §§3/5/6, D02-Q/R/S (préconditions pures) ; faits exhaustifs durables et variante concurrente D02-S dus L1-C sous PostgreSQL, jamais prouvés par cette ligne unitaire |
| L1A-11 | Montant zéro accepté, négatif refusé ; poids strictement positifs ; participants non vides ; payeur non participant accepté ; fractions équivalentes égales et normalisées | D03-B/C/J/K, partie valeurs ; aucune certification balance complète |
| L1A-12 | Injecter faits de Pots/V/objets différents, témoin temporel futur, cible sans fait, absence de preuve exhaustive : anomalie explicite, jamais succès ou no-op | D01-J et D02, cohérence pure des préconditions |
| L1A-13 | Deux calculs purs sur mêmes entrées : résultats égaux, états initiaux inchangés ; deux candidates identiques sur V ne sont pas deux commits ; recomposer avec nouvelle V et même observation | D02-K/L/M, modèle de réévaluation seulement ; CAS réel dû L1-C |
| L1A-14 | Construire à la main des compositions cohérentes à V5/V6 (défaut révisé, Financials identique), puis rejeter composantes croisées ; résolution conceptuelle à même V sans V−1 | D01-G/H/L, oracle mémoire ; reconstruction SQL non prouvée |
| L1A-15 | Tests ArchUnit production non vide, domaine pur, ports internes couverts, fixtures positives/négatives (§11) | Séparation domaine/infrastructure et A-01 ; pas de preuve transactionnelle CON-06 |

Oracles : tableaux de valeurs et révisions attendues écrits indépendamment du code de transition ; vérifier chaque composante, ID, motif et ensemble exact des changements, pas seulement V ou le nombre de résultats. Comparer les états avant/après un rejet ; un `assertThrows` seul ne prouve pas l'absence d'effet partiel. Pour les fractions, valeurs attendues établies par entiers rationnels indépendants (BigInteger côté oracle), jamais en appelant Fraction ou le calculateur testé pour construire l'attendu. Cas tabulaires prioritaires, permutations déterministes pour les lots et seeds fixes si génération utile ; ne pas introduire une bibliothèque générique de tests sans besoin concret.

**Contributions différées :** DOM-01 exige encore D01-A..M sur les intervalles/références réels, reconstruction directe PostgreSQL et histoire après rollback (L1-B/C). DOM-02 exige encore D02-K..P physiques, D02-Q/R/S avec faits autoritatifs, conservation des Financials historiques des Expenses supprimées et variante concurrente D02-S sous PostgreSQL en L1-C (création/modification d'Expense, payeur/participant et deux ordres de validation). Perte CAS, reprise complète avec observation initiale, absence de double effet, résultat/complétion et Bruno restent dus en L1-C/E et lots suivants. DOM-03 exige D03-A..O au complet : exactitude de chaque solde et des zéros, défaut à V, mélange Weight/Financials, ancienne V intacte et balances immuables/hors ordre (L1-D puis PostgreSQL/READ). Les oracles locaux L1-A ne remplacent ni EVT-02, ni CON-04/05/06, ni PRJ-02, ni SEC-01. Aucun SLO ou PASS de performance n'est inféré.

**Verdicts futurs :** PASS d'une preuve locale seulement après exécution fraîche de tous ses cas attendus, assertions satisfaites, zéro échec/erreur/skip dans son périmètre et artefacts traçables. FAIL si invariant contredit ou vérification en échec (y compris compilation) ; diagnostiquer sans élargissement automatique au réacteur complet. INCONCLUSIVE si collecte incomplète, tests manquants/ignorés, environnement ou traçabilité insuffisants pour conclure. « Non exécuté » reste distinct d'un résultat d'exécution INCONCLUSIVE ; aucun PASS dans ce document. Un BUILD SUCCESS avec skip ne suffit pas à certifier une couverture complète, comme le montre L0.

Conserver commande, SHA de départ, diff testé, versions outils, liste/méthodes attendues, XML frais, logs et verdict borné. Avant clôture L1, A-02 doit vérifier automatiquement la correspondance complète entre sources testées et commit publié ; les seules empreintes actuelles ne la résolvent pas. Le rapport de chaque étape doit distinguer impact production, fermeture Maven et certification normative restant due.

## 11. Renforcement ArchUnit A-01 prévu

Observation : `V3FoundationArchitectureTest.portDirection` sélectionne `com.kartaguez.pocoma.port..`. Les ports de [engine.write.pot.port](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/port/) ne sont pas couverts par cette sélection ; ils ne bénéficient que de la règle générale `applicationDirection`, moins stricte. Les fixtures actuelles de ports sont elles aussi sous `.fixture.port`, pas sous un engine. A-01 reste ouverte.

Renforcement proposé dans `architecture-tests`, avant clôture L1-A :

1. Couvrir explicitement les ports de premier niveau et les ports internes `engine.*.port.*` (sélecteur ArchUnit adapté aux packages réels, par exemple `..engine..port..`) ; importer la production sans fixtures/tests et affirmer que chacune de ces populations est non vide. Vérifier notamment PotContextPort/PotGlobalVersionPort, sans dépendre uniquement du nom suffixé Port.
2. Pour tous les ports, interdire infrastructure, composition/runtime/supra, Spring, JPA et SQL/JDBC, y compris types de retour, paramètres génériques, annotations et héritage. Maintenir la pureté des domaines et la direction entrante des engines.
3. Évaluer une liste positive adaptée aux contrats des ports internes. Ne pas étendre mécaniquement la liste actuelle aux seules classes `domain/port/contracts` : PotContextPort retourne déjà des types `engine.write.pot.context`. Inventorier ces dépendances applicatives pures, décider de leur statut et, si nécessaire, documenter des exceptions précises avec échéance ; ne pas autoriser tout `engine..` ni déplacer les types uniquement pour satisfaire un test.
4. Ajouter des fixtures positives et négatives dans une arborescence `engine.<capacité>.port.<rôle>`, dont une fuite vers implémentation applicative et des fuites infra/framework/SQL. Évaluer chaque fixture interdite séparément et vérifier que la violation nomme la classe attendue. Montrer qu'un sélecteur qui oublierait les ports internes ferait échouer le test de couverture.
5. Exécuter le gate global non filtré au §12 et consigner les règles, exceptions, populations couvertes et résultats. Un test de fixtures seul ne clôt pas A-01 ; une exclusion silencieuse des ports V2 non conformes non plus.

Aucune modification des règles ou fixtures n'est faite par le présent mandat documentaire. Si l'analyse des ports exige une modification de production hors domain-pot, appliquer la procédure d'escalade avant de la réaliser. A-02 reste séparément due avant clôture L1.

## 12. Déclaration préalable du périmètre Maven

Cette déclaration concerne la **future implémentation L1-A**, à confirmer sur la tête alors inspectée avant toute écriture de code, selon les §§3/14/17/29 de la politique. La résolution N-01 ne modifie que SPEC-10, ce plan et le canon ; aucun périmètre Maven n'est activé par ce mandat documentaire.

| Champ | Périmètre déclaré pour L1-A |
|---|---|
| Impact production attendu | `domain-pot` uniquement ; autres modules seulement après analyse d'impact explicite et redéclaration |
| Tests à modifier/créer | `domain-pot`, `architecture-tests` (A-01) |
| Preuve locale de feedback | Ancre producteur `domain-pot`, sans en faire une nouvelle slice canonique ni une preuve suffisante des consommateurs |
| Slice primaire consommateur | COMMAND : les commandes consomment les valeurs et invariants Pot partagés |
| Slice secondaire consommateur | PROJECTION : réutilisation/adaptation de Weight/Fraction et regroupements consommés par les calculs/projections V2 ; vérifier l'impact réel avant code, sans conserver artificiellement leurs contrats |
| Impact interdit sans redéclaration | Autres productions engines/ports/infra/composition/runtime/supra, POM, migrations ; aucun changement PostgreSQL/DDL de production dans L1-A |
| Gate architecture global | REQUIRED avant clôture L1-A : frontières interdites et A-01 explicitement concernées |
| Full reactor | NOT REQUIRED par défaut ; reste conditionné aux gates d'intégration/major wave et escalades de la politique |
| Vérification base métier L1-A | NONE : ni Pot PostgreSQL ni DDL ; fermeture transitive du gate global à distinguer ci-dessous |
| Prochain gate global obligatoire | Architecture non filtrée avant clôture L1-A ; A-02 avant clôture L1 ; gates wave/main selon politique |

Commandes prévues, toutes depuis `app/`, **non exécutées pour cette documentation** :

```bash
./mvnw -pl domain-pot -am test
./mvnw -pl runtime-command-consumption-worker,runtime-task-consumption-worker -am test
./mvnw -pl architecture-tests -am test
```

La deuxième commande compose les ancres canoniques COMMAND et PROJECTION, conformément aux §§11/14 de la politique : le changement d'un module partagé appelle une analyse de ses consommateurs, pas uniquement son test producteur. Leur exécution ne signifie pas qu'on modifie leur production. Si le code L1-A retenu est entièrement nouveau et non branché dans V2, justifier avant implémentation une réduction des slices consommatrices ; ne pas la déduire après coup des tests qui passent. Aucune slice WEB/EVENT/BINDING/LKV n'est ajoutée par simple prudence, aucun `-amd` par défaut.

Le gate `architecture-tests -am` a une fermeture large et peut lancer des tests PostgreSQL/migrations V2 existants, comme observé en L0 et précisé au §34 de la politique. Cette conséquence doit être annoncée et tracée ; elle n'est ni une preuve de persistance Pot V3 ni l'ajout de PostgreSQL au domaine pur. Ne pas filtrer le gate en prétendant l'avoir exécuté intégralement. Les migrations historiques ne sont pas relancées séparément par défaut ; aucune baseline V2 TRUSTED n'est présumée.

Escalade avant poursuite : identifier module/capacité, invariant et raison de tout changement hors périmètre ; distinguer nécessité de contrat partagé et couplage évitable ; réviser slices et preuves. Une API V2 cassée chez un consommateur est un impact à traiter explicitement, pas une raison d'ajouter des wrappers artificiels. Restructuration large des dépendances, clôture de wave majeure, intégration main ou stabilisation globale peuvent imposer `./mvnw test` ; une simple incertitude ou un test local en échec ne l'impose pas.

## 13. Risques, contradiction signalée et décisions ouvertes

| Point | Constat / décision restant due | Traitement attendu |
|---|---|---|
| N-01 — RÉSOLU DOCUMENTAIREMENT, 2026-10-25 | Arbitrage : SPEC-01 §§3/5/6 fait autorité pour DeleteShareholder ; correction de l'interdiction erronée de SPEC-10 DOM-01 et ajout de la ligne de dépendances DOM-02 | [SPEC-10 DOM-01](SPEC-10_Test_Certification.md#dom-01--protocole-et-oracles) : D01-M ; [DOM-02](SPEC-10_Test_Certification.md#dom-02--protocole-et-oracles) : D02-Q/R/S et critères PASS actualisés ; correspondances L1A-09/10 ci-dessus. Oracles définis, non exécutés, aucun PASS acquis ; concurrence D02-S à prouver en L1-C sous PostgreSQL |
| Structures et encapsulation | PotState/ShareholderState/ExpenseState, Versioned<T>, somme de résultats ou types spécialisés non validés | Choisir après revue les structures minimales empêchant les mélanges et états impossibles ; pas de squelette Java anticipé |
| Faits temporels et exhaustivité | Distinguer valeur, révision, début d'applicabilité, existence initiale et V de travail ; absence de référence active à prouver exhaustivement | L1-A vérifie cohérence interne ; L1-B/C devront garantir vérité et protection des faits ; décider leur représentation sans prolifération de Context |
| Capacité numérique | Fraction V2 stocke des long ; les intermédiaires BigInteger ne suppriment pas le risque de dépassement final | Qualifier et choisir représentation exacte ou refus technique explicite ; aucun arrondi ni nouvelle limite métier implicite ; preuves des limites avant réutilisation |
| Égalité et collections | Map/collection canonique, doublons d'une cible ou d'un participant, égalité de fractions et coût des copies | Décider la représentation et la gestion des doublons avant code ; conserver une cible logique unique et ne pas importer la sommation V2 par accident. L'ordre canonique pour digest/projection sera traité là où requis |
| Localisation des droits | Invariants minimaux nécessaires dès L1-A, matrice détaillée L1-D et autorité sécurisée L1-C | Éviter politique V2 restrictive copiée ou booléen client faisant autorité ; vérifier créateur indépendant et droits courants |
| Intégration V2 | Remplacer directement une classe partagée peut casser engines/adaptateurs, alors que leur production est hors périmètre prévu | Analyser le graphe et redéclarer avant extension ; nommer explicitement les ruptures intentionnelles et garanties encore dues ; aucune compatibilité fictive |
| A-01 / A-02 | Couverture des ports internes / correspondance code testé–commit publié incomplètes | A-01 avant clôture L1-A ; A-02 avant clôture L1 ; aucun de ces points n'est résolu par la rédaction du plan |

**Arbitrages de la revue L1-A : C-01 puis C-02 restent OUVERTS**, avant consolidation finale du contrat et autorisation explicite du code. Ces repères de revue ne désignent pas les protocoles de certification homonymes C-01/C-02 de SPEC-10. La résolution documentaire N-01 ne tranche aucun de ces choix.

Séquence proposée après revue et autorisation du code : fixer les représentations minimales et les oracles ; construire valeurs/composantes et contrôles de composition ; réaliser les trois transitions et les suppressions/créations pertinentes ; renforcer A-01 ; exécuter les preuves déclarées et publier leurs résultats bornés. Les dépendances PostgreSQL/événements/Consumption/READ restent planifiées dans les lots suivants. Une contradiction de norme se signale avant d'être contournée dans le code.

## 14. Vérification de ce mandat documentaire et reprise

Périmètre présent : résolution documentaire N-01 depuis `9a349bbcd86d900b34e927cc29e16dd172ee7ab4`, limitée à SPEC-10, ce plan et le canon ; SPEC-01 inchangée. Vérifications attendues avant publication : alignement SPEC-01/SPEC-10, recherche des contradictions résiduelles dans le corpus V3, résolution des liens relatifs et ancres Markdown, stabilité des identifiants de scénarios existants, distinction acquis/proposé/à prouver, `git diff --check`, liste exacte des fichiers et contrôle qu'aucun fichier production/test/migration/POM/script n'a changé. Aucun Maven, PostgreSQL ni full reactor requis ou exécuté pour cette seule documentation ; gate global requis pour la clôture future L1-A, pas pour publier ce plan.

Publier un commit documentaire cohérent sur `feat/v3-architecture` après vérification de sa tête distante, sans force et depuis un worktree isolé ; préserver les autres worktrees, index et modifications. Le SHA publié et les résultats des vérifications figurent dans le rapport de livraison et dans l'historique Git. La prochaine étape est l'arbitrage C-01 de la revue L1-A, puis C-02, avant consolidation finale du contrat et autorisation de l'implémentation Java. **L1-A reste non implémenté et aucune preuve fonctionnelle L1 n'est acquise.**
