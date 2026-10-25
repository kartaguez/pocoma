# V3-6 — L1 : conception et plan d’exécution L1-A à L1-E

**Statut : PLAN COMPLET À AUDITER ; L1-A à L1-E NON IMPLÉMENTÉS, NON EXÉCUTÉS, code non autorisé.** Date : 2026-10-25. Référence inspectée : `8764433da01fa010f01a56be01c8dc69a4ac8b3a`, branche de publication `feat/v3-architecture`. C-01/C-02 et N-01 acquis ; rationnels, faits et localisation des transitions consolidés ci-dessous. La validation indépendante de ce plan puis l’autorisation explicite d’implémentation sont deux préalables à la campagne. Les checkpoints après B et C et l’audit final ne peuvent être franchis par autoévaluation.

Ce plan technique est subordonné aux SPEC ; il ne constitue ni un nouveau canon, ni une autorisation de code, ni une preuve fonctionnelle L1. Le [canon, §11](V3_Canon_and_Roadmap.md#11-v3-6--passage-vers-l1) reste l'autorité de progression. L0 demeure clôturé documentairement et accepté pour la poursuite, avec I-01, A-01 et A-02 ouvertes selon le [rapport L0](L0_Execution_Report.md#independent-review-and-documentary-closure--2026-10-25). Aucun PASS L0 ou V2 n'est transféré à L1.

## 1. Sources, portée et niveaux de décision

Sources lues dans le dépôt : [SPEC-01](SPEC-01_Pot_Domain.md) (modèle et règles métier), [SPEC-02](SPEC-02_Identity_Domain.md) (autorité Identity), [SPEC-03](SPEC-03_Events_Requests_Consumables.md) (mutation et complétion), [SPEC-04](SPEC-04_Read_Projections.md) (sources historiques et balances), [SPEC-05](SPEC-05_Consumption_Discovery.md) (finalisation et reprises), [SPEC-06](SPEC-06_Supra_Contracts.md) (frontières applicatives), [SPEC-07](SPEC-07_Transverse_Requirements.md) (exigences), [SPEC-08](SPEC-08_Observability.md) (métrologie), [SPEC-09](SPEC-09_Performance.md) (budgets), [SPEC-10](SPEC-10_Test_Certification.md) (oracles et certification), [inventaire L0](L0_Technical_Inventory.md), [rapport L0](L0_Execution_Report.md), [canon](V3_Canon_and_Roadmap.md) et [politique du réacteur](../testing/Reactor_Verification_Policy.md). Les observations de code ci-dessous proviennent de la référence inspectée ; la reprise des travaux doit revalider les écarts si cette référence évolue. Aucune conversation externe n'est nécessaire pour appliquer ce plan.

**Acquis :** domaine Java pur indépendant de Spring, JPA, SQL et des transactions ; composantes immuables ; invariants protégés dans le domaine ; injection explicite par constructeur aux frontières applicatives ; Pot unité logique de cohérence et de version globale ; chargement sélectif ; réutilisation sélective V2 sans compatibilité artificielle. Les règles fonctionnelles de SPEC-01 ne sont pas rouvertes.

**Validé pour la conception L1 :** C-01, enveloppe conceptuelle `Versioned<T>` (§4), et C-02, résultat scellé générique `TransitionResult<T>` (§7), avec leurs invariants. Les détails d’encapsulation Java pourront être affinés sans en changer la sémantique. Ces repères sont distincts des protocoles de certification homonymes de SPEC-10. **Acquis également :** rationnels exacts arbitrairement grands (§4), faits typés minimaux autoritatifs (§5), transitions pures regroupées par responsabilité dans `domain-pot`, orchestration dans `engine-write-pot`, durabilité dans les adaptateurs (§15). Les structures Java non imposées par un invariant restent des choix techniques autonomes ; séquence et matrices ci-dessous sont soumises à l’audit du plan. Les autres exemples de noms Java ne sont pas des classes supplémentaires imposées. **À prouver :** tous les comportements L1, pureté effective des nouveaux composants, clôture A-01 et intégration ultérieure.

Objectif L1 : livrer un noyau Pot pur puis durable, vérifié et auditable dans l’ordre A→B→audit→C→audit→D→E→audit final (§§16–24).

Objectif L1-A : rendre exprimables et testables les huit composantes, leurs versions indépendantes, leurs transitions pures, les préconditions métier et l'atomicité logique d'une mutation. Les trois opérations du §8 sont les scénarios de conception obligatoires ; créations et suppressions complètent les invariants du noyau. Les droits métier nécessaires sont contrôlés à partir de faits explicites, sans simuler une authentification réussie.

Exclusions propres à L1-A : PostgreSQL, DDL et migrations de production, repositories/adaptateurs, CAS et verrous réels, transaction, historique durable, requests et résultats durables, publication d'événements, Consumption, READ, endpoints, Bruno, k6, activation. La résolution sécurisée d’EUI/bindingToken est une frontière applicative de C, pas une transition de domaine A ; le cycle des invitations reste hors L1. Le calcul complet relève de D, la matérialisation READ des lots suivants ; L1-A protège les valeurs et références financières locales et prépare leurs oracles.

## 2. Cartographie V2 ciblée — suivi I-01

Les liens mènent aux sources existantes, non à des livrables V3. REUSE/ADAPT/REPLACE/DEFER sont des orientations proposées ; aucun fichier Java n'est modifié par ce mandat.

| Sources inspectées | Observation V2 | Orientation L1 et propriété à préserver |
|---|---|---|
| [IDs](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/value/id/), [UserId](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/value/UserId.java) | UUID typés, égalité distinguant les classes d'identifiant | REUSE des identités stables ; préciser le nom Java du PocomaUserId sans introduire de nouveau domaine Identity |
| [Label](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/value/Label.java), [Name](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/value/Name.java), [Amount](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/value/Amount.java) | Valeurs immuables ; Amount refuse le négatif | REUSE après contrôle des invariants applicables et de l'égalité pour les no-op |
| [Weight](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/value/Weight.java), [Fraction](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/value/Fraction.java) | Weight accepte zéro ; Fraction normalise avec BigInteger intermédiaire puis `longValueExact()` | ADAPT : poids strictement positif, exactitude sans arrondi ; BigInteger pour les deux entiers, réduction canonique, sans plafond métier (§4) |
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

## 4. Contrats acquis et compositions minimales

### C-01 — Composantes versionnées : contrat conceptuel validé

```java
record Versioned<T>(
    long localVersion,
    long activeFrom,
    T value
) {}
```

Ce contrat conceptuel est validé ; ce fragment ne constitue pas une implémentation Java. Les détails d'encapsulation pourront être affinés pendant l'implémentation, sans changer la sémantique suivante :

- `localVersion >= 1`, `activeFrom >= 1`, `value != null` ; la valeur métier `T` est immuable, transitivement pour ses collections et leurs éléments (§3).
- `activeFrom` désigne la version **globale du Pot** de début d'applicabilité ; il n'est ni une version locale ni un horodatage.
- À la création, chaque composante naît en version locale 1 à la même V globale que les autres composantes du nouvel objet. Une nouvelle révision locale incrémente **exactement de 1** la précédente ; une composante inchangée conserve son enveloppe et sa version locale, sans recopie ni nouvelle révision.
- Toute nouvelle révision effective reçoit `activeFrom = candidatePotVersion` ; toutes celles d'une même mutation partagent cette unique candidate (§7).
- L'enveloppe ne contient ni `activeTo`, ni identifiant SQL, ni transaction, ni horodatage technique. La fermeture des intervalles historiques reste à L1-B selon SPEC-01 §2 ; l'absence de `activeTo` dans cette valeur du domaine ne supprime pas le contrat de persistance `[activeFrom,activeTo)`.

`Versioned<T>` ne garantit pas seul la cohérence des identités, de l'appartenance au Pot ou des versions de travail. Les compositions et transitions contrôlent ces invariants (§5), notamment l'applicabilité à la V de travail des enveloppes courantes et la cohérence des révisions candidates. Pour une composante existant à l'observation, la non-obsolescence se vérifie par `current.activeFrom <= observedVersion` ; comparer seulement les versions locales est insuffisant (§6).

Aucune méthode générique publique ne doit permettre de réviser arbitrairement une composante en contournant ses invariants métier : conserver le créateur, les identités et les préconditions relève des transitions contrôlées, pas du seul record.

### Rationnels et poids — décision validée

`Fraction` conserve un numérateur signé et un dénominateur strictement positif de type `BigInteger`, réduits par PGCD ; zéro = 0/1, dénominateur nul refusé. Comparaison et quatre opérations exactes ; division par zéro refusée. `Weight` encapsule un rationnel strictement positif, `Amount` reste non négatif. Aucun passage par double/float, aucune réduction finale en long, aucun arrondi ou plafond métier. L’overflow des compteurs de versions long est une erreur technique explicite avant toute écriture, jamais un retour à une version négative.

Aux frontières, convertir un décimal lexical en entier/puissance de dix exacte (y compris échelle négative) ou lire une fraction d’entiers ; une présentation arrondie éventuelle ne doit jamais être réinjectée comme valeur exacte. Transport/stockage des rationnels par paires d’entiers décimaux canoniques, sans perte JSON/JavaScript ; aucun nombre binaire approximatif. L1-A teste la conversion autonome minimale, L1-B son stockage ; les interfaces HTTP seront adaptées au lot Supra, sans endpoint ajouté en L1.

Dans `ExpenseFinancials`, la map immuable identifiée par ShareholderId porte un `specificWeight` optionnel : présent il remplace le défaut, absent il utilise le défaut à V. Refuser une cible dupliquée avant construction de la map plutôt que sommer ou écraser silencieusement ; l’ordre des entrées ne produit pas de mutation. Modifier un poids ne rééquilibre aucun autre poids stocké, et leur somme n’est pas normalisée. La proportion est exclusivement `effectiveWeight / sum(effectiveWeights)`. Le scénario rationnel de SPEC-10 D03-D clarifié fait référence pour les proportions qui évoluent par le dénominateur commun.

### Structures de composition restant proposées

| Concept étudié | Invariant qui pourrait le justifier | Coût / alternative / proposition à revoir |
|---|---|---|
| `PotState` | Composition cohérente d'un Pot à une V, références et versions homogènes | Utile pour composition pure et oracle complet ; ne pas en faire un agrégat intégral obligatoire à chaque mutation. Ne pas représenter « non chargé » par collection vide |
| `ShareholderState` | Même ShareholderId/Pot pour Header, Details et Weight | À retenir seulement si la construction contrôlée supprime un risque concret de mélange ; sinon composants nécessaires passés directement |
| `ExpenseState` | Même ExpenseId/Pot et cohérence Header/Details/Financials | Même examen ; ne pas imposer Details à une modification Financials sans dépendance métier |

Les trois structures de composition ci-dessus restent proposées ; `Versioned<T>` est acquis par C-01 et `TransitionResult<T>` par C-02 (§7). Le critère de choix est de rendre impossibles les incohérences du §5, avec le moins de concepts possible. Pas de nouvelle classe Context/Input/Snapshot/Result/Service/Port sans invariant ou frontière démontré. Les valeurs intrinsèquement invalides sont refusées à la construction ; les rejets d'une intention métier valide sont distingués des anomalies techniques. La nomenclature exacte des codes et exceptions reste à décider, sans imposer de framework.

## 5. Pot cohérent et chargement sélectif

Le Pot est la frontière de cohérence logique et de versionnement global ; il n'est pas une obligation de charger tous ses enfants. Une transition reçoit seulement les composantes ciblées et les faits nécessaires à ses invariants. Aucune lecture paresseuse, repository, callback SQL ou transaction cachée dans le domaine.

| Transition | Composantes et faits minimaux requis | Données sans dépendance directe |
|---|---|---|
| UpdatePotDetails | PotId, V de travail, PotHeader actif, PotDetails et son début d'applicabilité, observation et faits de droits courants | Expenses et poids ; seuls les faits de rattachement nécessaires à l'autorisation sont requis |
| UpdateShareholderWeights | Pot actif, V de travail, Header actif et Weight versionné de chaque cible, identité/appartenance, observation et droits courants | Financials des Expenses ; Details des cibles hors faits de droits réellement utiles |
| UpdateExpenseFinancials | Pot/Expense Headers actifs, Financials cible versionnés, faits d'existence/appartenance/activité des Shareholders référencés, existence à l'observation et droits courants | Révisions des noms/rattachements et des poids par défaut des références comme critères d'obsolescence |
| DeleteShareholder | Pot et Shareholder actifs, qualité de créateur, fait exhaustif d'absence de référence dans toute Expense active | Contenu intégral des Expenses si un fait autoritatif exhaustif suffit |

Les faits portent leur sujet (Pot/objet), leur V de travail et les témoins utiles. La transition refuse une entrée mélangeant identités, Pots ou V de travail, un témoin temporel hors de `1..V_travail`, une cible demandée sans ses faits, ou des clés incohérentes entre collections. Une liste partielle n'est jamais une preuve d'absence de référence. Un fait incohérent ou obligatoire manquant après existence établie est une anomalie technique, pas un no-op ni un rejet fonctionnel inventé.

Contrats de faits retenus, sans contexte universel : faits de droits `(potId, workingVersion, creatorId, actorUserId, présence de rattachement actif)` ; faits de références `(potId, workingVersion, shareholderId, première V de création, Header courant actif)` ; fait d’usage actif `(potId, workingVersion, shareholderId, référencé comme payeur ou participant)`. Des valeurs immuables dédiées ne sont introduites que si plusieurs données doivent être validées ensemble. Une preuve négative d’usage couvre **toutes** les Expenses actives du Pot, payeurs et participants, sans pagination partielle. Un test d’existence exhaustif peut s’arrêter au premier témoin positif. Le port applicatif décrit ce périmètre ; aucune chaîne SQL ni notion de verrou dans les faits du domaine. Le fait de création historique est distinct du `activeFrom` de la révision Header courante.

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

### C-02 — Résultats de transition : contrat conceptuel validé

```java
sealed interface TransitionResult<T>
    permits Rejected, Unchanged, Changed {
}
```

Cette somme générique scellée définit trois issues **exclusives** ; le fragment exprime le contrat conceptuel, avec encapsulation Java à affiner sans en changer la sémantique :

| Issue | Contenu et signification | Version globale |
|---|---|---|
| `Rejected` | Motif de rejet métier ; aucune révision candidate ni changement partiel. Ne représente ni panne technique, ni perte de CAS, ni incohérence des faits | Aucune candidate ; version de travail inchangée |
| `Unchanged` | Commande valide sans effet, après vérification de toutes les préconditions, notamment l'obsolescence ; aucune révision | Porte la version globale courante `currentPotVersion = V_travail` ; aucune candidate ; L1-C retourne cette version comme `resultingPotVersion` |
| `Changed<T>` | Changements métier typés `T`, immuables et effectifs ; ne signifie pas qu'une transaction a été validée | Porte `expectedPotVersion = V_travail` et `candidatePotVersion = expectedPotVersion + 1` |

Invariants de `Changed<T>` : au moins une modification effective ; chaque révision produite possède `activeFrom = candidatePotVersion` et sa version locale précédente +1 (création à 1) ; seules les composantes réellement modifiées sont révisées. Une mutation multi-cibles produit **une seule** version globale candidate. Le caractère non vide des changements doit être garanti par le contrat de `T` ou par une construction contrôlée du résultat ; un `Changed` vide n'est pas admissible.

Pour une mutation simple, `T` peut directement être `Versioned<PotDetails>` (ou la composante ciblée). Pour une mutation multi-cibles, `T` peut être un type métier contenant une collection immuable de révisions, par exemple `ShareholderWeightChanges`. Ces exemples n'imposent pas de classes supplémentaires sans nécessité. Aucun registre universel de composants, `Map<String,Object>` ou framework générique de mutations n'est introduit.

Une mutation multi-cibles valide d'abord toutes ses cibles puis construit son résultat complet ; aucune mise à jour en place ni résultat partiel observable. Le résultat inclut les composantes nécessaires pour appliquer la mutation sans reconstruire artificiellement le Pot entier ; son ensemble des changements doit correspondre exactement aux différences de valeurs et de révisions. Les inchangées conservent leur enveloppe et leur version locale ; elles ne figurent pas parmi les révisions produites. Aucun résultat partiellement modifié n’est permis en cas de `Rejected`.

**Candidate ne signifie pas durable.** Le domaine ne réserve pas V+1, n'effectue aucun CAS et ne déclare aucun commit. Deux transitions préparées sur la même V peuvent proposer la même candidate ; une seule sera validée durablement si elles entrent en conflit. L1-C jette la proposition perdante, recharge les faits à une nouvelle V de travail et réévalue avec l'observation initiale. Un conflit CAS est technique, pas un rejet métier automatique. Aucun compteur, boucle de retry ou horloge partagée dans la transition pure.

L1-B fermera les anciennes instances modifiées à la V effectivement validée et ouvrira les nouvelles à cette V. L1-C coordonnera transaction et publication : SPEC-03 exige un seul POT_EVENT par mutation effective (même multi-cibles), avec changedComponents ; aucun pour no-op ou rejet, mais résultat terminal et complétion. L1-A fournit des changements exploitables, sans construire une enveloppe événementielle universelle ni publier un événement prématuré.

## 8. Trois scénarios de conception obligatoires

Les chiffres suivants sont des fixtures proposées, pas des résultats exécutés. Chaque variante commence depuis sa fixture explicitement définie ; h/d/w/f sont des révisions locales.

### UpdatePotDetails

À V_travail=8, Pot actif, Header h1, Details d2 applicable depuis V5, label A et créateur C. L'auteur observe V6 et possède actuellement les droits de mutation.

| Variante | Oracle attendu |
|---|---|
| Demande label B | `Changed<Versioned<PotDetails>>` : expectedPotVersion=8, candidatePotVersion=9 ; PotDetails d3 seul, activeFrom=9, C strictement conservé ; enveloppes de PotHeader h1 et des enfants conservées |
| Demande label A | `Unchanged` portant V8 après contrôle des droits, activité et observation ; enveloppe d2 conservée, aucune révision |
| Details d2 applicable depuis V7 avec observation V6, demande A déjà présente | `Rejected` pour obsolescence, même si égalité ; aucune candidate ni révision |
| Header supprimé, auteur non autorisé, observation future ou faits d'un autre Pot | Refus ou anomalie selon nature ; aucun résultat effectif |
| Tentative de changer creatorId | L'opération de changement de label ne l'accepte pas ; la construction du nouvel état conserve le créateur, sans setter ni chemin de remplacement arbitraire |

La simple immutabilité d'une valeur PotDetails ne suffit pas : la transition doit empêcher de substituer un autre créateur entre deux états.

### UpdateShareholderWeights

À V_travail=12, observation V10, Pot actif, cibles A/B du même Pot actives : A w2=1 applicable depuis V7 ; B w3=2 depuis V9. Demande A=3, B=2. Droits courants valides.

| Variante | Oracle attendu |
|---|---|
| Lot valide | `Changed<T>` (par exemple `ShareholderWeightChanges`) : expectedPotVersion=12, candidatePotVersion=13 ; seule révision produite A w3, activeFrom=13 ; B conserve son enveloppe w3 depuis V9 ; aucun Header/Details ni ExpenseFinancials changé |
| B w3 applicable depuis V11 | `Rejected` global pour obsolescence, bien que B soit demandé à valeur égale ; A reste w2, aucune candidate ni révision |
| A et B demandés à valeur inchangée, toutes préconditions valides | `Unchanged` portant V12 ; enveloppes conservées, aucune révision |
| Dernière cible absente, supprimée, hors Pot ou poids zéro/négatif | Refus complet ; même la première cible valide ne change pas |
| Deux poids effectivement différents | `Changed<T>` attendu à V12, candidat à V13 : deux révisions locales +1, toutes deux avec activeFrom=13 |

L'absence d'effet sur ExpenseFinancials n'implique pas l'absence d'effet sur les futurs soldes à V13 : les participations sans specificWeight utiliseront les nouveaux défauts. Aucun chargement des Financials de toutes les Expenses n'est requis pour cette transition.

### UpdateExpenseFinancials

À V_travail=20, observation V16, Pot et Expense E actifs. E.f3 est applicable depuis V14 ; les Headers des Shareholders A/B/C existent depuis V2/V4/V8, appartiennent au Pot et sont encore actifs. Demande : amount=100, payerId=A (non participant), participants B sans poids spécifique et C avec specificWeight=2.

| Variante | Oracle attendu |
|---|---|
| Financials demandés différents, références valides | `Changed<Versioned<ExpenseFinancials>>` : expectedPotVersion=20, candidatePotVersion=21 ; E.f4 seul, activeFrom=21 ; cohérence montant/payeur/participants ; enveloppes de E.Header/Details et des Shareholders conservées |
| Nom ou userId de B modifié à V18 ; Weight de B modifié à V19 | Ces changements seuls ne rendent pas Financials obsolètes. Réévaluer séparément les droits courants de l'auteur ; si toujours autorisé, accepter |
| Un Shareholder référencé créé à V17 > observation V16 | Refus : référence non valide à l'observation, même si active aujourd'hui |
| Header de C supprimé, référence absente/hors Pot, Pot ou Expense supprimé | Refus sans état partiel ; ne pas résoudre par UserId ni révision technique |
| E.f4 courant applicable depuis V18 > V16, demande égale au contenu courant | `Rejected` pour obsolescence avant no-op ; aucune candidate ni révision |
| Montant négatif, participants vides ou specificWeight zéro/négatif | Invariant financier refusé ; aucune candidate |
| Valeurs financières identiques après normalisation et toutes préconditions valides | `Unchanged` portant V20 ; enveloppe f3 conservée, aucune révision |

Les poids effectifs ne sont pas figés dans Financials : pour un calcul à V, utiliser specificWeight sinon defaultWeight applicable à V. L1-A devra prouver l'intégrité des références et valeurs du résultat ; résolution historique PostgreSQL et balance exacte sur l'état composé sont dues ensuite. Si la transition n'a pas besoin du defaultWeight, elle ne le charge pas pour fabriquer un contrôle d'obsolescence.

## 9. Frontières avec les autres lots

| Lot | Responsabilité et contrat transmis |
|---|---|
| L1-A | Valeurs/composantes immuables, construction cohérente, préconditions pures, rejets/no-op/multi-cibles, candidate et changements typés ; aucune durabilité prétendue |
| L1-B | Schéma V3, lignes historiques et identités techniques, sélection à V, absence de trous/chevauchements, reconstruction exacte sans V−1 et accès groupés ; fournit les faits autoritatifs nécessaires |
| L1-C | Assemblage par constructeur, identité/binding fiables, chargement cohérent, maintien des préconditions jusqu'au commit, CAS global, rollback/retry complet avec observedVersion inchangé, mutations et complétions atomiques ; transforme la candidate en version durable |
| L1-D | Matrice détaillée des droits, rattachement/détachement, références financières, résolution des poids et calcul rationnel complet ; prolonge les invariants locaux déjà protégés en L1-A |
| L1-E | Intégration interne Pot et preuves conjointes bornées SPEC-10, A-02 et publication ; matérialisations, HTTP et certification globale restent aux lots suivants (§21) |

Le chevauchement des sujets est intentionnel : L1-A ne peut produire des Financials invalides en attendant L1-D ; L1-C ne doit pas recopier un second algorithme métier divergent. Les droits de suppressions/références sont déjà des préconditions pures à modéliser ; leurs faits exhaustifs et leur protection sous concurrence restent à prouver avec la persistance et l'exécution.

## 10. Matrice JUnit proposée et critères probatoires

Toutes les lignes ci-dessous sont **À IMPLÉMENTER / NON EXÉCUTÉES**. Les identifiants `L1A-*` sont des repères de réalisation, pas de nouvelles exigences normatives. Les liens DOM renvoient aux protocoles [SPEC-10, §3](SPEC-10_Test_Certification.md#3-domaine). Les preuves unitaires sont des contributions bornées, jamais un PASS global DOM-01/02/03.

| Repère | Cas à écrire / oracle décisif indépendant | Couverture normative / limite |
|---|---|---|
| L1A-01 | Vérifier C-01 : refuser localVersion<1, activeFrom<1 et value=null ; modifier les collections sources après construction, tenter de modifier celles exposées par les valeurs et changements typés C-02 ; réutiliser les enveloppes et états initiaux après transition : valeurs intactes, aucune mutation transitive ni chemin public de révision arbitraire contournant les invariants | Immutabilité, base DOM-01 ; pas de preuve SQL |
| L1A-02 | Créer Pot puis enfants : toutes leurs composantes locales à 1 ; modifier successivement chaque type : seule sa révision avance ; identités stables conservées | D01-A/B/C/D/E, partie pure |
| L1A-03 | C-01/C-02 : `Changed<T>` porte expectedPotVersion=V_travail et candidatePotVersion=expectedPotVersion+1 ; changements non vides exactement égaux aux différences ; chaque révision locale avance de 1 et porte activeFrom=candidatePotVersion ; enveloppes inchangées conservées et absentes des révisions produites ; anciennes valeurs intactes. Construction de Changed vide impossible ; Rejected/Unchanged sans révision ni candidate, Unchanged porte V courante | D01-K, D02-H ; commit/événement exclus |
| L1A-04 | Demande égale valide puis demande égale obsolète (y compris retour A→B→A) : vérifier current.activeFrom<=observedVersion pour chaque dépendance existant à l’observation, jamais les seules versions locales ; `Unchanged` portant V courante seulement si toutes les préconditions passent, sinon `Rejected` métier ; aucune candidate ni révision dans les deux | D02-I/J ; complétions dues ensuite |
| L1A-05 | Lot mixte valeurs modifiées/inchangées : un seul `Changed<T>` avec une candidate commune à tous les activeFrom produits, seules cibles modifiées révisées ; rendre la dernière cible obsolète/invalide et permuter les cibles : rejet global sans candidate, révision ni état partiellement modifié. Toutes cibles égales et valides : Unchanged sans révision | D02-G/H ; pas de preuve transactionnelle |
| L1A-06 | Exécuter toutes les variantes UpdatePotDetails du §8 ; créateur non-Shareholder conservé, aucun moyen de le remplacer | DOM-01/02 et SPEC-01 §§2/5/6 |
| L1A-07 | Toutes variantes UpdateShareholderWeights ; aucune révision d'ExpenseFinancials ; un changement de Details ne bloque pas le Weight compatible | D01-D, D02-B/D/G/H ; soldes ultérieurs dus L1-D |
| L1A-08 | Toutes variantes UpdateExpenseFinancials ; IDs stables, bon Pot, Headers actifs, référence antérieure à l'observation, indépendance Details/Weight, auteur encore autorisé | D01-H (composition), D02-D/N, D03-K ; historique/identité sécurisée non prouvés |
| L1A-09 | DeleteExpense : Header seul révisé, refus si Details/Financials ont changé ; Weight indépendant accepté. DeletePot : égalité globale exigée, Header seul et terminalité. DeleteShareholder : rejouer V1..V4 de D01-M en mémoire ; à V4 seul Header h1→h2 deleted, Details d1/Weight w2 inchangés, une seule candidate ; états V2/V3 conservés et mutations ultérieures refusées | D01-F/M (partie pure), D02-C/E/N ; intervalles/histoire durables L1-B/C et HTTP A11 exclus |
| L1A-10 | DeleteShareholder : Pot et Shareholder actifs requis ; refuser le participant non créateur sans candidate ni révision ; tester séparément payeur seul et participant seul d'Expense active ; autoriser le créateur si seules des Expenses supprimées référencent la cible ; aucune altération des Expenses ni de leurs Financials historiques fournis | SPEC-01 §§3/5/6, D02-Q/R/S (préconditions pures) ; faits exhaustifs durables et variante concurrente D02-S dus L1-C sous PostgreSQL, jamais prouvés par cette ligne unitaire |
| L1A-11 | Montant zéro accepté, négatif refusé ; poids strictement positifs ; participants non vides ; payeur non participant accepté ; fractions équivalentes égales, PGCD et zéro canoniques, résultats réduits au-delà de long ; conversion exacte 0.125=1/8 ; specificWeight optionnel remplaçant le défaut, aucune renormalisation ni modification d’autres poids | D03-B/C/J/K, partie valeurs ; aucune certification balance complète |
| L1A-12 | Injecter faits/enveloppes de Pots/V/objets différents, témoin temporel futur, cible sans fait, absence de preuve exhaustive : compositions/transitions signalent l’anomalie, que `Versioned<T>` seul ne garantit pas ; jamais `Changed`, `Unchanged` ni `Rejected` métier pour masquer une incohérence technique | D01-J et D02, cohérence pure des préconditions |
| L1A-13 | Deux calculs purs sur mêmes entrées : mêmes variantes et contenus C-02, enveloppes initiales intactes ; deux Changed identiques sur V ne sont pas deux commits. Après collision simulée, jeter la proposition perdante, recomposer tous les faits à la nouvelle V avec observedVersion initiale : recalcul déterministe, nouvelle expected/candidate et activeFrom si effet compatible, ou rejet métier si dépendance devenue obsolète ; la collision elle-même n’est pas Rejected | D02-K/L/M, modèle de réévaluation seulement ; CAS réel dû L1-C |
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

Cette déclaration concerne la **future implémentation L1-A**, à revalider sur la tête alors inspectée avant toute écriture de code, selon les §§3/14/17/29 de la politique. La présente consolidation du plan L1 ne modifie que la documentation (§14) ; aucun périmètre Maven n’est activé par ce mandat. N-01 reste acquis ; seules les clarifications normatives SPEC-01/10 du §13 sont apportées.

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

## 13. Décisions consolidées, contradictions et risques

| Point | Statut et traitement |
|---|---|
| N-01 | Résolu : SPEC-01 gouverne DeleteShareholder ; D01-M et D02-Q/R/S, y compris variante PostgreSQL, restent dus. Ne pas rouvrir |
| C-01 / C-02 | Validés, conservés aux §§4/7 ; détails d’encapsulation autonomes sans changer les invariants. Repères distincts des protocoles homonymes de SPEC-10 |
| Weight/Fraction | Décision validée, §4 ; `longValueExact()` final V2 à remplacer pour V3, aucun plafond métier |
| Faits / transitions | Décision validée, §§5/15 ; vérité autoritative B, protection jusqu’au commit ou reprise C ; pas de framework de faits ou de transitions |
| Contradiction D03-D | L’ancienne phrase « seules les parts dépendant du défaut évoluent » contredisait la formule de SPEC-01 : le dénominateur peut changer toutes les proportions. SPEC-10 corrigée, poids spécifiques stockés inchangés ; scénario exact ajouté sans nouvel ID |
| Ambiguïté P02-E | Ancien libellé laissant entendre que le rattachement userId modifie la répartition, contraire aux références ShareholderId de SPEC-01. SPEC-10 précise l’absence d’effet numérique du rattachement seul |
| Clarification SPEC-01 §4 | Nécessaire : l’exactitude existante ne précisait pas capacité arbitraire, saisie décimale/fractionnaire ni absence d’ajustement/normalisation stockée. Ces décisions fonctionnelles sont consignées dans leur autorité normative, pas uniquement dans le plan |
| SPEC-02 à 09 | Pas de contradiction fonctionnelle supplémentaire démontrée avec les décisions de la mission. Les formulations anciennes explicitement supplantées dans SPEC-10 ne rouvrent pas consentement/maintenance/C-03 ; les seuils physiques et qualifications restent aux lots concernés |
| I-01 | Compléments ciblés requis à chaque lot sur writers, mappings, contrats consommateurs ; aucun inventaire général nouveau |
| A-01 / A-02 | Non résolus par ce document : A-01 avant clôture A, A-02 avant clôture L1 (§22) |
| Rupture V2 | Les types, projections, codecs long et anciennes attentes peuvent casser. Inventorier avant code ; remplacement progressif, pas de passerelles ni double écriture artificielles |
| Coût des rationnels | Mesurer taille/CPU/allocations ; protection de transport selon SPEC-06, aucun plafond métier déduit des fixtures ; performance globale V3-7 |

Les décisions techniques ordinaires (noms de classes, encapsulation minimale, requêtes groupées, organisation des tests) ne requièrent pas une nouvelle décision humaine. Une contradiction normative non traitée ici, une fonctionnalité non arbitrée ou une refonte importante imposent l’arrêt décrit au §23.

## 14. Vérification documentaire et publication de ce plan

Mission présente : consolidation depuis `8764433da01fa010f01a56be01c8dc69a4ac8b3a` ; seulement ce plan, le canon et les clarifications indispensables de SPEC-01/10. Lecture des dix SPEC, rapports/inventaires L0, politique du réacteur et sources V2 ciblées. Contrôles avant publication : dépendances A→E et checkpoints, couverture des identifiants SPEC-10 (§21), liens/ancres Markdown, absence de statut de preuve acquis par rédaction, `git diff --check`, diff et périmètre exact des fichiers.

Aucune commande Maven, aucun test d’implémentation, aucune base PostgreSQL ni benchmark à exécuter pour cette mission documentaire ; aucun global gate requis et aucun full reactor. Les commandes ci-dessous sont **prévues**, non exécutées. Les résultats des seuls contrôles documentaires et le SHA publié sont donnés dans le rapport de livraison. Aucun manifeste L0 ni rapport historique n’est réécrit.

Commit documentaire dédié, travail isolé pour préserver index/modifications/worktrees préexistants. Contrôler la tête distante, publier par avance rapide sans force vers `feat/v3-architecture`. Si elle diverge, arrêter la publication et examiner les changements ; ne jamais écraser le travail concurrent. Après publication : attendre l’audit du plan et l’autorisation explicite ; **ne pas démarrer L1**.

## 15. Architecture d’exécution et contrats entre lots

Les noms suivants sont des repères Java de réalisation, pas une hiérarchie de classes obligatoire. Garder les modules existants ; aucun nouveau module, framework ou moteur générique. Regrouper les fonctions de transition par responsabilité cohérente : Pot, Shareholders, Expenses et calcul financier. Ne pas créer un Service par commande. Le domaine reçoit des valeurs et rend `TransitionResult<T>` ; il ne charge rien et ne connaît ni Spring/JPA/SQL, ni CAS, ni transaction, ni heure de commit. L’engine choisit les lectures, appelle la transition et coordonne les ports ; les adaptateurs portent les opérations durables.

| Contrat à rendre concret | Localisation / entrée et sortie | Livraison et garantie |
|---|---|---|
| Composantes et transitions | `domain-pot`, valeurs §§3–8, faits minimaux §5 → C-02 | A ; déterminisme, construction protégée, aucune mutation des entrées |
| Lecture courante sélective | Ports internes `engine-write-pot.port.persistence`, adaptateurs `infra-persistence-primary-jpa` ; Pot/cibles/observation → V de travail, enveloppes et faits typés | B ; une vue cohérente, pas « collection vide = non chargé » |
| Lecture historique | Port applicatif de reconstruction par `(PotId,V)` et lectures groupées de clés de composantes → composition ou contenus exacts | B ; `[from,to)`, détection d’absence/multiplicité, sans état courant ni V−1 ; réutilisable par les futurs projecteurs |
| Usage actif de Shareholder | Recherche exhaustive du Pot à V : payeur OU participant d’Expense active → fait positif/négatif | B ; couvre tout le périmètre, pas un échantillon de références |
| Application des révisions | Port de persistance : identité Pot, expected V et Changed typé → écritures d’intervalles, nouvelles révisions et métadonnée de V | B fournit les primitives sous transaction appelante ; C contrôle le CAS et le commit. Aucune méthode publique de mutation sans demande durable dans l’application |
| Protection de V | `PotGlobalVersionPort` adapté : avance conditionnelle V→V+1 et validation protégée de V sans incrément pour no-op/rejet | C ; succès explicite ou conflit technique ; pas de booléen ignoré ni mise à jour inconditionnelle |
| Autorité de l’acteur | Frontière applicative depuis requesterEui et bindingToken déclarés → User opérant et témoin d’autorité WRITE protégeable | C ; lecture/fence jusqu’au commit ; ni User choisi par le client ni booléen d’accès public |
| Unité terminale Command | `engine-write-pot` avec `engine-consume-command`, ports existants request/result/event/transaction et adaptateurs WRITE | C ; résultat/TERMINAL/complétion et effet éventuel indivisibles, demande rejouée sans nouvel effet |
| Soldes complets | Calcul pur `domain-pot`, `(PotId,V, ensemble Shareholders attendu, Financials actifs et défauts à V)` → map immuable de fractions signées | D ; toutes les clés et zéros, sans balance précédente ; un Header Pot terminal produit une décision explicite « pas de balance D » à transmettre au futur matérialiseur |
| Collecte de preuve | Scripts/manifestes de test, hors domaine et hors tables métier | A à E ; commandes/XML frais, méthodes attendues, limites ; A-02 automatique E |

**Choix de simplicité obligatoires :** noms métier explicites, fonctions courtes, responsabilités cohérentes, collections immuables, invariants à la construction, dépendances injectées par constructeur. Toute nouvelle interface doit correspondre à une frontière réelle ou à deux usages qui en ont besoin. Toute factory, mapper, DTO, port, couche ou duplication doit avoir une justification écrite dans la revue du lot. Les tests utilisent des tableaux attendus et oracles indépendants, pas les helpers métier testés pour fabriquer l’attendu. Aucun cache, mécanisme de répartition, optimisation préventive ou abstraction universelle.

**Rupture progressive V2 :** privilégier le remplacement des types et usages réellement concernés. Pour garder A/B compilables sans transformer prématurément les writers V2, un namespace V3 distinct est permis pour les nouvelles composantes irréconciliables avec les regroupements V2 ; le motif et les consommateurs restant à convertir sont inventoriés. Ce n’est ni une API de compatibilité ni un deuxième chemin activé. Ne pas recopier l’algorithme métier entre engines ; retirer les anciens usages au moment où leur frontière passe en V3. L1-E n’annonce pas la disparition des projections V2 qui relèvent encore des lots READ ; elles restent explicitement historiques et non certifiées V3. Toute expansion de production hors déclaration est analysée et redéclarée avant modification.

## 16. L1-A — Noyau pur et preuves unitaires

1. **Objectif et périmètre :** implémenter les huit composantes, valeurs, versions indépendantes, compositions minimales et toutes les transitions pures SPEC-01 hors cycle durable des invitations. Couvrir CreatePot, créations groupées de Shareholders, modification Details/Weight, création Expense, modification Details/Financials, trois suppressions, rattachement/détachement ordinaire. Les règles de droits et références nécessaires sont protégées dès A ; D complète la matrice et le calcul intégral, sans période où une transition accepte une valeur invalide.
2. **Java et modules :** `domain-pot` (`value`, identités, composantes, transitions par responsabilité, politique métier) ; tests du module et `architecture-tests` pour A-01. Adapter les intentions de `PotHeader`, `Shareholder`, `ExpenseHeader`, `ExpenseShares` ; ne pas conserver leurs mutateurs V2 dans les nouveaux contrats. Pas de POM ni de production engine/infra à modifier dans cette déclaration initiale.
3. **Contrats :** C-01/C-02 §§4/7, faits §5, dépendances §6, conversion rationnelle exacte §4. Une map contient une seule entrée par cible/participant ; doublons refusés avant toute perte d’information. Les motifs métier sont typés/stables et séparés des anomalies techniques ; pas de registre de codes générique. Création Pot : état initial absent, V1 unique ; les créations ultérieures attendent V courante et proposent V+1.
4. **Dépendances :** L0 accepté, décisions consolidées, autorisation du code et plan audité. Aucun composant B/C n’est simulé comme preuve de durabilité.
5. **Ordre :** revalider l’impact I-01 et enregistrer le manifeste attendu → Fraction/Weight/Amount/IDs → huit composantes et C-01 → faits/compositions → C-02 et créations → trois scénarios §8 → autres modifications/suppressions/rattachements → matrice L1A-01..15 → A-01 et gates. Construire les candidats seulement après validation de toutes les cibles.
6. **Migration :** les UUID, labels et invariants Amount compatibles sont réutilisables ; remplacer Weight nul, Fraction long et fusion implicite des participants. Aucun DDL, aucune migration de données, aucun endpoint. Recenser les tests V2 à maintenir/remplacer/retirer avec la propriété correspondante ; ne pas étendre silencieusement le lot pour rétablir une API obsolète.
7. **Tests :** matrice §10 complète, valeurs au-delà de long, PGCD/signe/zéro, divisions, décimaux exacts, permutations/doublons, refus/no-op et état initial intact, droits courants, références à l’observation. Intégration structurelle ArchUnit et fixtures négatives par dépendance. PostgreSQL métier : NONE ; les suites transitoires des consommateurs/gate global restent distinctes.
8. **Oracles :** contributions pures D01-A..M, D02-A..J/N/Q/R/S, D03-C/J/K/O ; D02-K/L/M seulement modèle mémoire, sans PASS CAS. §10 précise les limites de chaque contribution. Aucun PASS global DOM/CON n’est permis au titre de A.
9. **Gates :** déclaration §12 : primaire COMMAND, secondaire PROJECTION, feedback `domain-pot`, architecture globale REQUIRED pour A-01 et dépendances interdites, full reactor NOT REQUIRED. Commandes G-A et G-ARCH au §24. Base métier NONE ; coût historique éventuel du gate explicitement tracé.
10. **PASS et clôture :** toutes les méthodes attendues exécutées sans failure/error/skip, trois scénarios et créations/suppressions exacts, entrées immuables, changements typés non vides, A-01 démontrée sur production et fixtures, revue de simplicité conforme. Un gate qui ne compile pas est FAIL. Une API V2 cassée non résolue interdit la clôture.
11. **Livrables et preuves :** commit(s) `feat(v3-l1-a): ...`, inventaire des sources/tests touchés et des ruptures, table cas→méthode→oracle, XML/logs bruts, comparaison de valeurs/révisions attendues, populations ArchUnit et exceptions précises. Rapport §22, aucune preuve PostgreSQL métier revendiquée.
12. **Risques et arrêt :** duplication de domaine, gonflement des faits, fuite d’implémentation dans les ports, réutilisation implicite des mutateurs/floats V2. Corriger dans le lot ; si la correction impose une nouvelle frontière de production ou une réorganisation importante, analyser avant poursuite (§23). A seul n’est pas un checkpoint obligatoire ; après PASS et commit, B peut commencer dans la campagne autorisée.

## 17. L1-B — Persistance, histoire et faits autoritatifs

1. **Objectif et périmètre :** persister les huit composantes V3, reconstruire directement Pot@V, lire sélectivement les faits nécessaires aux transitions et intégralement les faits financiers historiques. Fournir les primitives de révisions ; aucune mutation applicative autonome ni ordonnanceur dans B.
2. **Java et modules :** ports internes de `engine-write-pot`, adaptateurs/entities/repositories/migrations de `infra-persistence-primary-jpa`, tests PostgreSQL/composition ciblés. Réutiliser le prédicat des repositories `core` et les contrôles d’appartenance de `JpaHistoricalPotSnapshotSourceAdapter` ; remplacer les regroupements V2 et ses lectures de parts par Expense (N+1). La source de balance historique doit fournir les défauts à V, pas les poids courants. `infra-tx-spring` reste utilisé par composition ; modifications de sa production hors scope sans analyse.
3. **Contrats :** §15 : lecture sélective et historique, fait exhaustif d’usage, écriture de révisions sous transaction appelante. Tous les faits d’une lecture portent Pot/V/cibles ; défaut d’intégrité après existence établie = anomalie technique. Séparer la première V d’existence du Header et le début de sa révision actuelle. Une absence initiale d’objet reste distinguable d’un objet créé mais incomplet.
4. **Dépendances :** A PASS et commit identifié, invariants des valeurs figés ; résultats d’A conservés. Les readers/writers B sont testés directement sous transactions réelles ; leur succès ne prouve pas encore l’orchestration C.
5. **Ordre :** complément I-01 (mappings, migrations, graphe et consommateurs) → DDL V3/contrat de stockage exact → mappings → primitives de fermeture/insertion → lecteurs à V → lectures groupées sélectives → faits d’autorisation/références/absence → tests d’anomalies et redémarrage → gates et rapport → publication A/B → **arrêt audit B**.
6. **Migrations :** emplacement Flyway neuf `classpath:db/v3-pot/migration`, schémas aléatoires de banc, bootstrap explicitement sélectionné sans scan historique V2 ni `ddl-auto=create`. Ne jamais modifier la fixture L0 en schéma métier. Les anciennes chaînes PRIMARY V1–V31 et READ V1–V14 restent intactes ; aucune base externe migrée. Pas de baseline V2 TRUSTED supposée. Le schéma neuf est un contrat de développement V3, pas une équivalence V2 ni une migration à chaud.

   Tables de composantes/révisions : ID technique local, identité métier stable et Pot propriétaire, version locale positive, `activeFrom>=1`, `activeTo` nul ou `>activeFrom` ; unicité identité/composante/version et au plus une révision ouverte, détection explicite de trous/chevauchements par lecteur et writer. Les FK de participants ciblent les identités Shareholder du même Pot, jamais une révision ; une participation est attachée à la révision précise de Financials et peut porter un poids spécifique nul au sens SQL seulement lorsqu’il est absent (deux entiers présents ensemble sinon). Aucun cascade-delete de l’histoire.

   Stocker numérateur et dénominateur en **texte décimal canonique d’entiers** dans ce premier schéma : aucune précision `numeric(p,s)`, bigint ni virgule flottante. Dénominateur positif, cohérence de nullité et syntaxe contrôlées ; writer/lecteur valident réduction et signes sans corriger silencieusement une donnée corrompue. Cette représentation simple évite un plafond de précision SQL ajouté au métier ; les limites physiques de ressources sont qualifiées séparément. Fermer uniquement les anciennes révisions modifiées et insérer les nouvelles dans la transaction appelante, à la V fournie après contrôle C ; création d’objet = composantes toutes à 1, suppression = Header seul. Un rollback annule les deux côtés de l’intervalle.
7. **Tests :** JUnit/PG réel, bootstrap uniquement V3-Pot, version PostgreSQL et catalogue exact, Flyway restart/validate. Fixtures D01 V1..V9 et D01-M via les vrais writers B ; lecture aléatoire de V, bornes exactes, anomalies injectées dans banc isolé, anciennes V intactes. Round-trip rationnels >long, spécifique absent/présent et décimal exact. Faits négatifs avec nombreuses Expenses dont la dernière référence est bloquante, payeur seul/participant seul, Expenses supprimées, mauvais Pot. Lecture groupée sans détails inutiles, pas N+1. Cohérence de snapshot sous mutation concurrente ; pas de certification de stabilité jusqu’au commit avant C.
8. **Oracles :** D01-A..J/L/M complets côté historique interne ; D01-K rollback/no-op de primitive seulement, orchestration due C. D02-Q/R/S fourniture des faits ; D03-D/E/M résolution à V et round-trip. Capturer tables/versions/intervalles, clés et valeurs de chaque composante, pas seulement les compteurs.
9. **Gates :** primaire COMMAND, secondaire PROJECTION car contrat historique partagé ; G-B (§24), architecture REQUIRED si contrats de frontières/dépendances changés (prévu ici), full reactor NOT REQUIRED. DB CURRENT V3-Pot ; pas de gate HISTORICAL séparé. Interdit : changement READ, HTTP, domaine Identity, Discovery/Engine génériques. Si le graphe réel impose un autre consommateur, redéclarer avant son changement.
10. **PASS et clôture :** histoire exacte pour chaque V, anomalies non masquées, faits négatifs exhaustifs, aucun arrondi, chaque writer transactionnel rollbackable, pas de données V2 chargées dans le banc V3, suites attendues et gates sans défaut bloquant. A et B ont leurs commits et preuves séparés ; le PASS B autorise la soumission à audit, **pas C**.
11. **Livrables et preuves :** commit(s) `feat(v3-l1-b): ...`, DDL/catalogue, contrats Java, inventaire I-01/mappings et choix de bootstrap, SQL/snapshots attendus et observés, XML/logs, plans/comptages des lectures groupées, manifestes d’A et B. Publier le SHA B descendant d’A et l’emplacement durable de toutes les preuves avant suspension.
12. **Risques et arrêt :** intervalle apparemment unique mais trou réel, absence fondée sur page partielle, mauvais défaut à V, limite numérique SQL, bootstrap legacy déclenché par Spring. Aucun résultat du banc L0 ne remplace le test métier. Au checkpoint, attendre un audit indépendant ; défaut bloquant → correction(s) B identifiée(s), rerun des preuves affectées puis nouvelle revue avant C (§23).

## 18. L1-C — Mutation durable, CAS et reprises

1. **Objectif et périmètre :** transformer une intention durable en décision métier et, si Changed, en commit Pot unique. Maintenir jusqu’au commit les faits d’autorité et de domaine, y compris no-op/rejet. Implémenter les frontières Command nécessaires ; pas le moteur Discovery/Engine V3 complet ni ses Consumptions READ.
2. **Java et modules :** orchestration par responsabilité dans `engine-write-pot`, intégration bornée `engine-consume-command`, contrats Command/événements réellement concernés (`contracts-command`, `domain-event`), ports request/result/transaction/binding existants, writers `infra-persistence-primary-jpa`, composition explicite `composition-runtime-spring`/Supra Command si nécessaire au chemin réel. Réemployer savoir-faire CAS, `PrimaryEventTransactionRunner`/suffixe, guards et `CommitAcknowledgementProxy`, sans transférer leurs PASS. Pas de nouveau module. Tout changement de production d’un moteur générique ou d’Identity exige analyse/scope explicite ; la consommation V3 complète reste ultérieure.
3. **Contrats :** §15, expected/candidate séparés, conflit technique distinct de Rejected ; requesterEui/intention/observedVersion immuables ; résultats terminaux et événement de complétion minimaux SPEC-03. Un résultat Changed n’est jamais un reçu de commit. Le writer ne peut modifier une composante absente des changements typés ; vérifier correspondance exacte avec changedComponents de POT_EVENT.
4. **Dépendances :** audit indépendant A/B accepté et défauts bloquants revérifiés ; A et B PASS. Les préconditions pures et primitives B sont réutilisées telles quelles. Aucun démarrage C sur seule autoévaluation.
5. **Ordre et protocole à implémenter :**

   a. Charger la demande durable et le claim acquis ; si déjà terminale, retrouver le résultat original sans répéter l’effet. Vérifier l’intégrité de l’intention et garder l’observation initiale.

   b. Préparer sélectivement une vue cohérente à V via B, résoudre l’acteur WRITE depuis EUI/token, calculer C-02 hors transaction finale si utile. Aucun effet durable ni réservation de V candidate à ce stade.

   c. Dans l’unique transaction WRITE finale, protéger le slot par verrou exclusif, vérifier le token et l’échéance avec une heure DB fraîche **après** l’attente ; retenir le verrou jusqu’à résolution physique. Protéger ensuite l’autorité de l’acteur et la ligne de version Pot selon un ordre documenté commun aux chemins. Vérifier que V est toujours celle des faits ; sinon abandon technique intégral, pas de faux rejet métier. Si la préparation est faite dans la transaction, les lectures et protections doivent fournir la même garantie.

   d. Les mutations Pot concurrentes passent toutes par cette autorité de version, y compris créations d’Expense contre DeleteShareholder et changement de rattachement contre autorisation. CAS Changed V→V+1 puis révisions B ; pour Unchanged/Rejected, verrouiller/valider V **sans l’incrémenter**, puis décider à partir des faits ainsi protégés. L’absence de CAS d’écriture métier ne dispense pas du maintien des préconditions ni de l’autorité jusqu’au commit. CreatePot utilise l’unicité de PotId et crée V1 atomiquement, pas un CAS sur une ligne supposée existante.

   e. Écrire effet éventuel, histoire, résultat terminal et statut ; préparer événements exacts. Terminer toutes écritures/validations métier, flush JPA et contraintes avant le suffixe de séquencement ; publication dense `POT → COMMAND_COMPLETION`, ou seule complétion pour no-op/rejet. Aucune acquisition de nouveau verrou métier après les verrous de séquences. La suppression du slot appartient à la même unité et respecte cet ordre. Un seul POT_EVENT si effet multi-cibles, zéro si no-op/rejet ; resultingPotVersion = candidate validée ou V courante pour no-op.

   f. Sur erreur technique/CAS perdu : rollback total ; comptabilisation conditionnelle unique d’un échec constaté sous claim valide, retryAfter puis nouvelle acquisition et tentative complète, **aucun retry dans le même claim**. N configurable, quarantaine à N, aucune purge ni terminalisation fictive ; crash/expiration/commit incertain ne consomment pas de budget par supposition. Si le moteur partagé n’est pas encore V3, fournir uniquement le raccord applicatif et le banc réel de ces opérations, sans modifier discrètement son cycle global.

   g. Sur réponse COMMIT perdue : relire état durable ; TERMINAL/résultat/slot absent constate l’effet unique, PENDING/slot présent conduit à reprise conditionnelle après résolution. Aucune répétition aveugle, aucun état UNKNOWN durable. Une tentative suivante recharge **tous** les faits et conserve observedVersion.

6. **Migrations/adaptations :** adapter les primitives de demande/résultat, slot WRITE et événements nécessaires dans l’emplacement V3 explicite ; ni double écriture V2/V3 ni anciens événements par enfant. Les tables partagées réutilisées doivent respecter intégralement SPEC-03/05 ; toute différence est inventoriée. Les familles non utilisées dans L1 ne sont pas déclarées certifiées. Vérifier les codecs rationnels/intention pour qu’ils n’introduisent ni long ni double. Retirer les comportements V2 « incrément même sans changement », expiration d’attestation externe recontrôlée au Worker contrairement à SPEC-02, et claim déjà expiré accepté sans takeover.

   **Frontières encore externes à L1 :** Registration/Link/Unlink V3, admission HTTP/idempotence, Discovery et scheduling V3. C implémente et teste la consommation du contrat d’autorité EUI/token avec un adaptateur disponible conforme ; si cette autorité réelle n’est pas encore livrée, utiliser une fixture PostgreSQL d’autorité explicitement identifiée derrière le port pour les interleavings internes, sans convertir BindingId V2 en garantie V3. Même règle pour acquisition initiale : fixture de demande PENDING et de slot/claim, finalisation par les vrais writers C. Le rapport marque ces raccords **non certifiés de bout en bout** et fournit les tests de contrat à reprendre aux lots Identity/Consumption. Un mock ne peut servir à revendiquer une preuve PostgreSQL de verrou/fence. Si le contrat d’autorité ne peut être protégé jusqu’au commit sans décision d’architecture nouvelle, arrêter ; aucun bypass d’autorisation.
7. **Tests :** unitaires d’orchestration résultat/branche/erreur, intégration par constructeur sur vrais writers et manager, PG multiconnexions avec barrières. D02-S : création/modification d’Expense × payeur/participant × deux ordres de validation ; aucune Expense active ne référence un Shareholder supprimé. Autres courses : Details contre Weight compatibles ; Financials contre Weight ; DeleteExpense contre ses Details ; DeletePot contre toute mutation ; révocation d’autorité/rattachement entre lecture et commit ; no-op/rejet devenu périmé ; même demande deux finalisations ; D02-P avec deux intentions équivalentes sous requestIds distincts puis rejeu d’un seul : deux résultats propres, aucun double effet de la même demande. Injecter panne avant/après chaque writer, flush, contrainte différée et séquencement ; erreurs JDBC/JPA interceptées, deux ordres d’écriture, commit réellement perdu via proxy. Observer après résolution par connexion indépendante, pas par exception côté Worker ni sleep.
8. **Oracles :** D01-K et D02-A..S côté interne, E02-A/B/C/F/G/H/I/J sur Command Pot ; E01-A/B/C/E/F/H sur les deux familles utilisées ; C03-E/F/G/H/J et C04-A..I/K/L côté finalisation Command ; C05-A..M et compléments N/quarantaine côté raccord ; C06-A/E/F/G/H/I/L/M. Chaque variante applicable est manifestée ; ni familles Identity/Invitation, ni Discovery/READ, ni Bruno ne sont certifiés par ces contributions.
9. **Gates :** primaire COMMAND ; secondaires EVENT et PROJECTION pour contrats d’événements et source historique consommés ; BINDING uniquement si son contrat ou sa production change, après redéclaration. G-C, architecture REQUIRED pour les frontières de transactions/fencing et contrats interdits, full reactor NOT REQUIRED sauf restructuration majeure démontrée. DB CURRENT V3. Interdits sans redéclaration : HTTP, READ, cycle Identity, LKV, ordonnanceur Discovery complet.
10. **PASS et clôture :** tous les cas internes manifestés satisfaits, une seule unité physique prouvée, aucune demi-révision/terminalisation, conflit CAS réévalué sur nouvelle acquisition, no-op/rejet cohérent, aucune faille D02-S ; fixtures externes et limites nommées, pas de prétention E2E. Aucune voie de writer non inventoriée. Commit(s)/preuves publiés → **arrêt audit C**, même si toutes les suites locales passent.
11. **Livrables et preuves :** commit(s) `feat(v3-l1-c): ...`, diagramme ou table de l’ordre des protections et des autorités physiques, liste des writers et du raccord moteur, chronologie des barrières, V observées/de travail/candidates/committées, tables métier/histoire/résultats/événements/slots avant/après, PID/XID réels, erreurs interceptées, journaux de commit perdu et XML frais ; aucune valeur secrète exposée.
12. **Risques et arrêt :** rejet fondé sur fait périmé, absence d’usage non protégée, comparaison de l’heure au début d’une longue transaction, deadlock introduit après séquencement, effets REQUIRES_NEW, boucles cachées sous un claim. Tout échec de garantie physique non résolu bloque l’audit ; correction C et revérification indépendante obligatoires avant D.

## 19. L1-D — Droits, références financières et balances exactes

1. **Objectif et périmètre :** achever la matrice métier SPEC-01, la résolution des poids à V et le calcul intégral exact des balances. D complète A/C et réutilise leurs transitions ; il ne crée pas une politique d’autorisation parallèle ni une projection READ.
2. **Java et modules :** `domain-pot` (politique, résolution des poids, calculateur pur regroupé avec les finances), lectures financières groupées `infra-persistence-primary-jpa`, orchestration/ports de `engine-write-pot`. Adapter l’arithmétique utile de `PotBalancesCalculator` et le calcul complet de `CalculatePotBalancesAtVersionService` ; convertir les consommateurs réellement touchés de `domain-projection`/`engine-consume-projection-task` sans maintenir l’API différentielle dans le chemin V3. Pas de nouvelle tâche Balance.
3. **Contrats :** calcul §15 sans previousBalances, clés Shareholder complètes, zéros explicites, données exactes à même V. Le résultat est immuable et signé ; le payeur reçoit +amount, participants −parts, somme exacte zéro. L’ensemble attendu provient de la composition métier exacte à V, incluant les Shareholders attendus sans Expense et leurs zéros ; les cas de suppression préservent intégralement les anciennes compositions, sans réintroduire de contribution d’Expense supprimée. Header Pot terminal = décision d’absence de balance D ; les anciennes versions actives restent calculables après D.
4. **Dépendances :** audit C accepté, corrections bloquantes revérifiées ; A valeurs/transitions, B histoire/lectures, C exécution protégée. Les scénarios D s’exécutent sur le chemin C pour prouver que la politique réelle et la lecture historique sont celles utilisées.
5. **Ordre :** compléter I-01 calculateur/consommateurs → matrice de droits tabulaire → rattachement/détachement et références → résolution défaut/spécifique à V → calcul complet d’Expense puis Pot → tests D03 → intégration C/B → gates et commit D → E. Les défauts A/C découverts sont corrigés avec traçabilité ; une correction bloquante d’un périmètre audité déclenche retour à son checkpoint avant poursuite.
6. **Migrations/adaptations :** aucun schéma nouveau attendu hors compléments justifiés B ; exactitude dans projections/DTO existants seulement si directement consommés par le calcul testé. Retirer logique différentielle V−1 et hypothèse de poids obligatoire V2 dans le chemin V3 ; pas de balance persistée dans WRITE uniquement pour simuler READ. Aucun plafond de cibles ni rééquilibrage automatique.
7. **Tests :** unitaires positifs/négatifs pour chaque cellule SPEC-01 §5 et par statut actif/supprimé : créateur sans Shareholder, participant actif, étranger ; suppression réservée au créateur ; auto-rattachement libre à soi seulement ; interdiction de substituer un tiers ou remplacer directement U1→U2 ; auto-détachement ou détachement par créateur ; multi-rattachement général autorisé. Invitation : tester seulement la règle pure d’éligibilité (pas déjà rattaché, libre/actif), sans prétendre livrer son cycle token/expiration. Références ShareholderId indépendantes de userId/nom, créées avant observation, toujours actives au commit. D03-A..O, fixture §10/SPEC-10, spécifique 0.125/fractionnaire, réductions >long, ancien V intact, mêmes nombres sous ordre différent ; rejet de poids zéro et de participants vides. Intégration B/C et PG avec modifications de poids/Financials compatibles, rattachements/suppressions et résolution d’anciennes V ; comparer chaque solde à l’oracle indépendant, pas seulement la somme.
8. **Oracles :** D03-A..O pour valeurs/calcul et histoire interne, D01-D/H/L, D02-B/D/G/H/N/Q/R/S, A11-A et A11-D uniquement côté données/calcul. P02-A..H/L et aspects numériques de P02-M sont des contributions préparatoires ; P02-I/J/K et matérialisation immuable/hors ordre restent aux lots READ. Aucune certification SEC-01/INV-01 sans leurs parcours réels.
9. **Gates :** primaire COMMAND, secondaire PROJECTION (contrat financier consommé), G-D ; règles A-01 conservées, gate global NOT REQUIRED si aucune dépendance/frontière nouvelle depuis C, sinon REQUIRED selon politique §17 avant clôture ; full reactor NOT REQUIRED. DB CURRENT V3 pour histoires et concurrence ; aucun gate HISTORICAL séparé. Pas de WEB/EVENT/BINDING/LKV ajouté par précaution.
10. **PASS et clôture :** matrice métier complète sans autorité client, valeurs exactes de chaque Shareholder/zéro, conservation parfaite des anciennes V, défaut/spécifique conforme et aucun changement automatique des autres poids, interfaces de calcul indépendantes de V−1. Tous cas internes/gates manifestés exécutés ; toute règle financière non arbitrée arrête le lot. La simplicité du calcul et l’absence de couches redondantes sont des critères de clôture.
11. **Livrables et preuves :** commit(s) `feat(v3-l1-d): ...`, table droits/cas/méthodes, fixtures financières et fractions attendues pour chaque V, snapshots PG et comparaison de chaque clé/valeur, profil indicatif de coût/allocation petit/moyen/grand sans PASS de performance, XML et limites des futurs tests READ.
12. **Risques et arrêt :** confondre poids conservé et proportion conservée, répartition par User au lieu de Shareholder, balance calculée depuis le présent, optimisation différentielle, normalisation des poids. Après D PASS/commit la campagne peut continuer E ; ne pas franchir à nouveau un périmètre audité rendu invalide par une correction.

## 20. L1-E — Intégration interne et dossier de certification L1

1. **Objectif et périmètre :** prouver que le domaine, les faits, l’histoire, la mutation et les calculs des lots A–D composent un noyau Pot cohérent ; consolider les preuves applicables et leurs limites. Certification **L1 interne**, pas certification V3 globale, pas activation ni clôture définitive avant audit final.
2. **Java et modules :** tests d’intégration des composants A–D, `composition-runtime-spring`, `architecture-tests`, scripts/manifeste/collecteur `scripts/testing`, documentation de progression. Corriger la production seulement pour défauts démontrés, dans son lot d’origine et avec scope/commit identifié. Pas d’ajout de runtime ni d’endpoint pour obtenir artificiellement un E2E.
3. **Contrats :** composition explicite par constructeur, autorités physiques identifiées, état WRITE exact et API interne de calcul/reconstruction à V, résultats C-02 puis terminalisation distincts. A-02 (§22) constitue un contrat de provenance vérifié automatiquement ; pas une simple empreinte volontairement jointe au rapport.
4. **Dépendances :** A/B/C audités selon checkpoints, D PASS, inventaire des preuves attendues et des raccords non encore réels. Une absence de composant externe n’est pas comblée par un mock déclaré conforme à SPEC-10 ; elle reste une limite explicite.
5. **Ordre :** figer périmètre et manifestes → assembler composants réels → scénario V1..V9 et D01-M via chemin C → matrice complète droits/finances/no-op/rejet/multi-cibles → reprise/rollback et histoire après incidents → intégration consommateur et revue de simplicité → G-E/G-ARCH/G-FULL → A-02 sur commit exact → dossier final/publication D/E → **arrêt audit final L1**. Aucune édition exécutable après preuve finale sans rerun justifié.
6. **Migrations/adaptations :** démarrage V3 isolé, validate/restart et absence de tables historiques chargées par erreur. Nettoyer les anciens appels du périmètre converti et les tests rendus caducs en conservant leur oracle utile. Aucun transfert de données de production, pas de suppression des chaînes historiques ou des autres worktrees.
7. **Tests :** parcours interne réel request PENDING/claim → transition → commit ou rollback → relecture Pot@V et calcul exact ; création/modification/suppression, no-op/rejet, lots mixtes, changement de défaut sans révision Financials, changement userId sans effet numérique, D02-S et anciennes versions calculées après D. Composition Spring réelle, mêmes PID/XID pour writers indivisibles, manager incohérent refusé, séparations WRITE/READ contrôlées sans fabriquer de projection métier. PG et reprises de chaque scénario obligatoire selon son propriétaire ; tests purs et slices réexécutés sur le contenu final. Bruno/k6 et tests HTTP V3 non requis ici, explicitement reportés aux lots de parcours et à V3-7.
8. **Oracles :** fermer les contributions internes DOM-01/02/03 définies au §21, variantes Pot EVT-01/02 et Command CON-03/04/05/06 ; aucun verdict global d’exigence SPEC-10 si des supports/variantes obligatoires manquent. Comparer tous les états attendus et tous les effets durables, y compris absence d’effet sous panne. Reprendre les guards L0/A-01 sur les nouveaux composants, sans requalifier les anciennes preuves.
9. **Gates :** primaire COMMAND, secondaires PROJECTION et EVENT comme consommateurs réellement intégrés ; G-E, architecture globale REQUIRED pour clôture du jalon structurel L1 et full reactor REQUIRED **une fois à la clôture du jalon majeur L1**, conformément aux §§19/21 de la politique. Ce coût est planifié à E, pas ajouté à chaque classe ou lot. DB CURRENT V3 et suites historiques transitoires éventuellement exécutées par fermeture Maven ; pas de certification BASELINE V2 revendiquée. Ajouter WEB/BINDING seulement si leur production/contrat a effectivement changé, avec analyse préalable.
10. **PASS et clôture :** preuves internes obligatoires fraîches sans failure/error/skip/manquant, suite et manifestes complets, A-01 close, A-02 automatique close, gates réussis, limites SPEC-10 exactes et aucune régression non résolue. PASS technique E permet la publication et l’audit, **pas la clôture définitive L1**. Un skip facultatif historique reste visible : couverture globale INCONCLUSIVE, jamais requalifiée PASS intégral ; il doit être distingué du verdict interne et soumis à l’audit final.
11. **Livrables et preuves :** commit(s) `test(v3-l1-e): ...`, rapport récapitulant SHAs A–E/corrections, fichiers modifiés, déclaration/résultat de chaque gate, agrégats tests/failures/errors/skips et cas manquants, artefacts PG bruts, matrice §21 instanciée, limites, A-02 et état distant final. Mettre à jour le canon sans déclarer clos L1 avant audit indépendant.
12. **Risques et arrêt :** élargissement en campagne V3 complète, passage d’un succès Maven à une certification non démontrée, preuve sur sources différentes du commit publié, sources changées après test. Corriger avec nouveaux commits et preuves ; publier sans force puis suspendre toute clôture définitive et toute progression suivante jusqu’au verdict indépendant final.

## 21. Couverture SPEC-10 et limites de certification

Cette affectation est une **liste de travaux et de supports attendus**, pas une seconde matrice normative ni des résultats. SPEC-10 reste l’unique autorité des exigences et de leurs critères PASS. Avant chaque lot, le manifeste énumère chaque cas/variante → méthode/fixture → oracle → gate → artefact attendu, puis l’exécution enregistre le résultat réel. Les plages ci-dessous incluent leurs amendements et compléments non numérotés. Ne pas perdre D02-P, D02-S transactionnel, les compléments CON-05 ou C06-N/O/P en se limitant aux exemples du plan.

| Exigence / scénarios SPEC-10 | Contribution L1 et propriétaire | Preuve restant due hors L1 / limite de verdict |
|---|---|---|
| DOM-01, D01-A..M | A pureté/révisions ; B reconstruction PG directe, intervalles et anomalies ; C rollback/mutations ; E parcours intégré | Bruno lecture V3 quand READ/Supra réels ; pas de transfert d’un snapshot interne à l’API |
| DOM-02, D02-A..S + variante D02-S | A dépendances/no-op/atomicité logique ; B faits ; C concurrence/reprises/commit ; D droits/références ; E intégration | Support Bruno/resultingPotVersion externe et exécution sous Engine V3 complet à reprendre ; aucune collision CAS simplement appelée obsolescence |
| DOM-03, D03-A..O | A valeurs ; B défauts historiques ; D calcul et droits, tailles/seeds ; E intégration PG | Balance READ immuable/hors ordre conjointe PRJ-02 et Bruno ; pas de PASS DOM-03 global anticipé |
| ID-01 I01-A..Q ; ID-02 I02-A..Q et C-01 consentement | C/D vérifient seulement l’utilisation d’un acteur/fait autoritatif, sans mutation Identity | Lots Identity/admission/consentement et V3-7 ; fixtures d’autorité ne certifient pas Registration/Link/Unlink |
| EVT-01 E01-A..H | C/E séquencement des familles POT/COMMAND_COMPLETION, ordre des verrous, rollback et répétitions | Cinq familles, Registration à deux événements, Invitation et charge complète aux lots événements/Identity ; pas de PASS EVT-01 global |
| EVT-02 E02-A..J | C/E variantes Command Pot, no-op/rejet, requestId, panne et récupération | Variantes Identity/Invitation, vraie chaîne Worker/admission future ; L1 ne certifie que les variantes exécutées |
| EVT-03 E03-A..J | E vérifie que le noyau ne produit aucune tâche READ intermédiaire | Catalogue et Discovery des sept familles, trois Consumptions Pot directes, Invitation et reconstruction aux lots événements/Consumption/READ |
| INV-01 INV-A..J | A/D préconditions métier réutilisables, multi-rattachement général distingué de l’invitation | Création durable/confidentialité/token/expiration, READ, atomie et races de l’invitation aux lots dédiés ; aucun PASS INV-01 L1 |
| CON-01 C01-A..L ; CON-02 C02-A..L | Pas de Discovery/checkpoint L1 ; seuls les invariants de finalisation Command sont repris C | Moteur Discovery/Engine et cinq séquences, fenêtre sûre, enrôlement, reset ; sans prétention de couverture des slots depuis leurs seules absences |
| CON-03 C03-A..L | C/E validité au verrou final, token remplacé, expiration et commit ; seulement parcours Command exercé | Propriété/takeover/renouvellements sous Engine V3, multi-Worker et toutes Consumptions ; A/B aucune preuve |
| CON-04 C04-A..L | C/E unicité finale Command, effets/slot indivisibles, commit inconnu, revue des writers | Branches READ et intégration du moteur V3 réel ; les garanties du banc ne valent pas couverture système |
| CON-05 C05-A..M + compléments AUD-V3-007/Q | C/E raccord tentative complète, échec durable unique, retryAfter, N/quarantaine, observation conservée | Acquisition/discovery automatique, réactivation administrative réelle, équité entre slots/versions/Consumptions, métriques et k6 au lot Consumption |
| CON-06 C06-A..P | A A-01 ; C/E C06-A/E/F/G/H/I/L/M pour writers Pot réels et autorité physique | C06-B Identity, C/D/J/K/N/O/P READ et Worker mixte au périmètre complet ; pas de PASS global CON-06 |
| PRJ-01 P01-A..L et compléments | B API historique exacte, A composants immuables | Matérialisation PotVersion/SubobjectVersion/CurrentPot, conflits de contenu, hors ordre et HTTP aux lots READ |
| PRJ-02 P02-A..M, exception D | D/E calcul intégral et exact, zéro, défaut à V, terminal D sans résultat financier | Immutabilité/idempotence et insertion atomique READ/slot, versions hors ordre, coût E2E au lot READ/V3-7 |
| PRJ-03 P03-A..O | B faits de rattachement/creator à V ; D politique pure | Garde collective, tombstones et visibilité monotone aux lots READ ; liste non autorisante |
| READ-01 R01-A..N et SUPRA-READ-01 | B sources à V et D calcul uniquement | Lecture figée, minVersion, complétude/manifeste/lots/absence N+1, HTTP et autorisation aux lots READ/Supra |
| SEC-01 S01-A..O | A/D droits métier courants et historique des faits B ; C consommation EUI/token protégée | AuthN réelle, CurrentBinding READ, droits historiques API, non-divulgation et Keycloak au lot sécurité/READ |
| SEC-02 S02-A..K et propre CurrentBinding | C refus WRITE sur autorité révoquée dans le banc explicite | Propagation Unlink réelle, fenêtre READ acceptée et tokens à V aux lots Identity/READ |
| ADM-01 ; S-03 | C respecte intention/provenance immuable de la demande injectée au banc | Admission HTTP sans lecture métier, seuils, clés/portée/canonicalisation/idempotence aux lots Supra ; un requestId répété n’est pas une preuve S-03 |
| S-01 ; S-02 | B composition exacte de toutes références utilisable ultérieurement | Navigation keyset, manifeste complet ordonné, détails groupés bornés et protections HTTP aux lots READ/Supra |
| A10-A..K | C/E état WRITE/provenance et résultat Command interne seulement | Les onze scénarios HTTP, IdentityResult, lecture indépendante par requesterEui aux lots résultats/READ/Supra |
| A11-A..N | A/C A11-A pur/durable ; B histoire V<D ; D calcul tardif actif et décision sans balance D | A11-B..N nécessitant projections/HTTP/charge aux lots READ/Supra/V3-7 ; A11-L/M sans utiliser 404 seul comme oracle |
| REC-01 RCV01-A..N et C-02 maintenance | B reconstruction WRITE exacte, hors procédure de reset READ | Exclusivité réelle, cinq préfixes B, sept Consumptions READ et READY au lot reconstruction ; L1 n’ajoute pas de reset |
| OPS-01 O01-A..N | C/E diagnostics locaux tentatives/fautes seulement | Polling, équité, backlog/âge, quarantaine et progression du même Pot V+1 aux lots moteur/charge |
| PERF-01 F01-A..J et C-03 | B/D mesure indicative de requêtes/taille/coût du calcul, sans SLO | V3-7 : populations/durées/sondes à valider, 1/10/50/s, budgets 500 ms/1 s/3 s et grand Pot 2 s, deux jalons distincts Pot W≥V/Balance V, DeletePot interne, rattrapage ≤5 min |

L1 ne réduit aucun oracle reporté et ne déclare aucune exigence globale PASS lorsqu’un support obligatoire n’a pas été exécuté. Le dossier E peut conclure **PASS du périmètre L1 interne** et laisser la certification globale V3 non exécutée ou INCONCLUSIVE selon les preuves disponibles. Un gate Maven global vert prouve la non-régression de son code présent, pas l’existence de composants V3 encore absents. Les dix SPEC demeurent applicables aux interfaces futures.

## 22. Preuves, commits et A-02

Pour chaque sous-lot puis chaque lot, enregistrer avant exécution : SHA de départ, portée attendue, manifestes méthodes/variantes obligatoires, commande exacte et répertoire, configuration base/versions/outils/seeds, limites. Le rapport de lot comporte : impact production observé, slices déclarées, fermeture Maven distincte, franchissements inattendus et raisons, global gate requis/exécuté, full reactor exécuté ou non et motif, mode DB CURRENT/BASELINE/HISTORICAL et bootstrap effectif, inventaire des fichiers, SHA(s) de code/corrections, push et divergence distante, état du worktree.

Conserver par commande : code retour, début/fin, log complet, XML **frais** et bruts, nombre de tests/failures/errors/skips, liste des méthodes attendues, manquantes/dupliquées/ignorées et sorties PG. Pour les tests paramétrés, toutes les variantes requises sont vérifiées, pas seulement la présence de la méthode. Archiver les artefacts hors du seul `target/` effaçable avant un clean ; fournir chemin durable et empreintes dans le rapport. Les données sensibles sont remplacées dans les exports publics ; les assertions privées n’exposent pas les tokens. Une archive inaccessible à l’auditeur rend la preuve INCONCLUSIVE.

Verdict : **PASS** uniquement sur périmètre explicite après toutes preuves obligatoires exécutées et satisfaites ; **FAIL** pour invariant/gate en échec, y compris compilation ; **INCONCLUSIVE** pour preuve manquante, skip obligatoire, provenance/collecte insuffisante ou environnement empêchant de conclure. « NON EXÉCUTÉ » est le statut actuel des futurs lots, pas une exécution réputée inconclusive. Un succès de commande ne masque aucun skip ; les résultats globaux et les résultats bornés sont rapportés séparément.

**A-02 à implémenter avant clôture L1, amorcé dès A :** étendre le collecteur L0 par un contrôle automatisé effectif de correspondance au commit publié. Procédure attendue :

1. Créer un commit de code H comprenant toutes les entrées exécutables/configurations/migrations/POM/scripts/manifeste attendus ; refuser au départ un index ou des sources sales/non suivies qui pourraient affecter le build. Inventorier aussi les entrées externes (JDK, Maven, image PG/digest, options JVM/variables de configuration non secrètes, dépendances résolues) et les sorties générées qui servent d’entrées.
2. Exécuter les gates depuis H avec collecte fraîche. Comparer automatiquement le manifeste complet des entrées réellement testées à l’arbre Git H avant **et après** chaque commande ; refuser tout ajout/modification/suppression d’entrée. Des empreintes stockées sans cette comparaison ne résolvent pas A-02. Ne pas inclure les sorties XML/logs dans les entrées.
3. Si le rapport est committé ensuite en P, vérifier automatiquement `H..P` : seuls fichiers documentaires/artefacts explicitement autorisés peuvent différer ; aucune source, ressource, migration, test, POM, configuration ni script exécutable. Vérifier de nouveau les empreintes des entrées contre P et conserver H/P et l’exacte liste d’exclusions documentaires. Si une entrée change, refaire les gates affectés ; une modification exécutée après le full reactor exige sa revalidation selon l’impact final.
4. Avant push contrôler la tête distante puis publier P par avance rapide, sans force. Après push, lire la tête distante exacte et vérifier automatiquement qu’elle est P et que ses entrées Git correspondent au manifeste testé. Conserver la sortie du contrôle et son verdict avec les artefacts. Une publication sans correspondance vérifiée ne peut clore A-02 ni L1 ; aucun simple fingerprint de worktree ou déclaration manuelle ne suffit.

Commits identifiables par lot, aucun squash mélangeant A/B/C/D/E dans la campagne ; corrections désignent leur lot et les preuves remplacées. Les preuves de A/B doivent rester consultables au checkpoint B, celles de C au checkpoint C, toutes les preuves D/E au final. Les rapports marquent les anciennes exécutions remplacées, sans les effacer ni requalifier leurs verdicts. Le canon suit l’état réel, les limites et la prochaine étape autorisée ; aucun rapport ne prédate un PASS.

## 23. Campagne autonome et checkpoints indépendants

| Étape | Entrée requise | Action autorisée et sortie | Arrêt obligatoire |
|---|---|---|---|
| Préparation présente | Mandat documentaire | Publier ce plan et ses clarifications, sans Java/tests d’implémentation | Attendre validation indépendante du plan puis autorisation explicite du code |
| Segment A→B | Plan audité + autorisation explicite du segment | Implémenter/corriger A puis B, commits distincts, gates et preuves réels ; publier la tête B contenant A | **Checkpoint après B** : publier SHAs et preuves A+B, suspendre ; audit indépendant accepté et corrections bloquantes revérifiées avant C |
| Segment C | Audit A/B accepté + autorisation C | Mutations/CAS/reprises, commits C, preuves et publication | **Checkpoint après C** : suspendre ; audit indépendant accepté et corrections bloquantes revérifiées avant D |
| Segment D→E | Audit C accepté + autorisation D/E | Droits/finances puis intégration, commits distincts, A-02 et gates finaux ; publier D/E et preuves | **Checkpoint final** : suspendre toute clôture définitive L1 ; attendre audit indépendant L1 complet |
| Après audit final | Verdict indépendant explicite | Mettre à jour les statuts/corrections dans le canon selon le verdict | Aucun démarrage de jalon ultérieur ni activation implicite |

Une autorisation initiale peut couvrir plusieurs segments, mais ne remplace jamais la levée explicite des checkpoints par un audit indépendant. À chaque arrêt : rapport autoporteur avec SHA publié, périmètre, commandes réellement exécutées, tests/failures/errors/skips, preuves PG et emplacements, limites, PASS/FAIL/INCONCLUSIVE, questions et prochain segment interdit jusqu’à levée. Un audit indépendant peut être statique : il précise alors les preuves non reproduites ; il ne devient pas une nouvelle exécution.

À l’intérieur d’un segment autorisé, Codex choisit les détails techniques ordinaires, exécute les tests déclarés et corrige les défauts courants sans validation par classe. Arrêt si contradiction normative non traitée, décision fonctionnelle nouvelle, refonte importante du découpage/architecture, gate obligatoire non résolu ou checkpoint atteint. Les seuls élargissements ordinaires de preuve suivent la politique : expliquer module/capacité/cause, redéclarer la portée **avant** modification puis exécuter la slice nécessaire ; ils ne justifient pas un full reactor automatique. Une restructuration importante demande validation avant reprise.

Tout défaut bloquant découvert après audit qui invalide sa garantie rouvre le checkpoint concerné ; corriger, réexécuter les preuves affectées et faire revérifier avant de poursuivre le segment dépendant. L’absence de réponse d’audit ou l’écoulement du temps n’est jamais une levée. Aucun checkpoint n’est franchissable sur la seule autoévaluation de Codex.

## 24. Gates Maven prévus et déclarations de portée

Toutes les commandes suivantes sont à lancer depuis `app/` lors de l’implémentation autorisée ; **aucune n’a été exécutée pour ce mandat documentaire**. Les ancres suivent la politique §§5–10/14 ; les tests producteur sont du feedback, pas de nouvelles slices canoniques. Aucun `-amd` ni architecture-tests ajouté par défaut aux commandes locales.

| Gate | Commande prévue | Usage et statut requis |
|---|---|---|
| G-A feedback | `./mvnw -pl domain-pot -am test` | Valeurs/transitions ; ne suffit pas à clore un contrat partagé |
| G-A consommateurs | `./mvnw -pl runtime-command-consumption-worker,runtime-task-consumption-worker -am test` | COMMAND primaire + PROJECTION secondaire, clôture A |
| G-B feedback | `./mvnw -pl infra-persistence-primary-jpa -am test` | Mappings/PG, pas une nouvelle slice |
| G-B consommateurs | `./mvnw -pl runtime-command-consumption-worker,runtime-task-consumption-worker -am test` | COMMAND + PROJECTION, clôture B |
| G-C | `./mvnw -pl runtime-command-consumption-worker,runtime-event-consumption-worker,runtime-task-consumption-worker -am test` | COMMAND primaire, EVENT + PROJECTION secondaires, interfaces de publication/histoire consommées |
| G-D | `./mvnw -pl runtime-command-consumption-worker,runtime-task-consumption-worker -am test` | COMMAND + PROJECTION, calcul et consommateurs |
| G-E | `./mvnw -pl runtime-command-consumption-worker,runtime-event-consumption-worker,runtime-task-consumption-worker -am test` | Intégration interne bornée des consommateurs réels |
| G-ARCH | `./mvnw -pl architecture-tests -am test` | Global non filtré REQUIRED A (A-01), B (frontières), C (transaction/contrats), E (jalon) ; D seulement si condition §17 de la politique satisfaite |
| G-FULL | `./mvnw test` | REQUIRED à E, jalon majeur L1 ; pas dans les boucles A/B/C/D ; ni substitution à G-ARCH ni certification des composants absents |

Les commandes multi-slices composent les commandes canoniques. Pour diagnostic seulement, des sélections `-Dtest` sont permises avec garde explicite des méthodes attendues, sans remplacer un gate non filtré ni masquer un test manquant. Aucun gate supplémentaire HISTORY n’est déclenché à chaque changement de domaine.

Déclarations à enregistrer avant chaque lot :

| Lot | Impact production attendu / interdit | Base métier | Global / full / prochain gate | Escalade |
|---|---|---|---|---|
| A | `domain-pot` ; interdits engines/infra/POM/runtime/DDL sans redéclaration ; tests ArchUnit autorisés | NONE ; coût DB transitif distinct | ARCH requis, FULL non requis ; prochain ARCH A | Usages partagés cassés ou ports non conformes : analyser avant expansion |
| B | Ports applicatifs + `infra-persistence-primary-jpa` ; interdits READ/HTTP/Identity/Consumption générique | CURRENT V3-Pot neuf | ARCH requis, FULL non requis ; prochain checkpoint B puis ARCH C | Writer/read contract consommé ailleurs ou bootstrap legacy : redéclarer et corriger |
| C | Chemin Command Pot, contrats/ports/writers/composition §18 ; interdits moteur complet/HTTP/READ/cycle Identity/LKV | CURRENT V3 ; autorité externe fixture explicitée si absente | ARCH requis, FULL non requis ; prochain checkpoint C | Changement contrat Binding → BINDING+COMMAND ; restructuration importante → arrêt pour validation |
| D | Domaine/engine/readers financiers et consommateurs de calcul §19 ; interdits matérialisations/HTTP/moteur | CURRENT V3 | ARCH conditionnel, FULL non requis ; prochain E ARCH+FULL | Nouvelle migration/frontière ou correction invalidant un audit : redéclarer/rouvrir checkpoint |
| E | Composition/tests/scripts/preuves + correctifs bornés ; interdits nouveau parcours public/activation | CURRENT V3 et suites transitives identifiées | ARCH et FULL requis ; prochain audit final | Production hors périmètre A–D ou gate obligatoire non résolu : arrêt |

L’analyse I-01 revalide le graphe Maven à chaque entrée ; conserver les ancrages existants même si leur nom historique contient « task ». Les modules ne sont pas renommés pour correspondre au vocabulaire fonctionnel avant besoin démontré. Une production BINDING/WEB/EVENT/LKV réellement modifiée exige sa slice, pas l’inclusion systématique de son runtime dès qu’un type partagé est lu. Si les implémentations A sont neuves et non branchées, une réduction des consommateurs doit être justifiée **avant code**, jamais après les tests.

La politique constate **Trusted baseline: NONE** ; le candidat V23 n’est pas une baseline certifiée des chaînes V31/V14. Le bootstrap V3 neuf B n’est pas une certification BASELINE V2. Ne pas relancer séparément les migrations historiques par défaut ; le gate global non filtré et le full reactor peuvent toutefois les lancer via leurs suites existantes. Enregistrer alors la commande, les chaînes réellement exécutées et ce coût, comme en L0. Ne pas annoncer « aucune migration historique exécutée » seulement parce qu’aucune commande Flyway séparée n’a été lancée. Une future baseline TRUSTED permet le parcours courant prévu par la politique après certification explicite, jamais par présomption.
