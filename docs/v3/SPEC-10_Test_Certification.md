# Pocoma SPEC-10 — Tests et certification

**Statut : protocoles normatifs définis, AUCUN test V3 exécuté ni PASS revendiqué.**  
**Consolidation :** 2026-10-24. Les décisions finales AUD-V3-001 à AUD-V3-011 prévalent ; une exigence abandonnée n'est pas réintroduite par un scénario historique.

## 1. Principes de certification

Chaque preuve dispose d'un ID stable, d'une précondition, d'un oracle observable et d'une méthode de reproduction. Distinguer conceptuellement défini, implémenté, exécuté, PASS, FAIL et INCONCLUSIVE. Une exécution absente ne constitue jamais PASS. PostgreSQL réel, interleavings déterministes, rollback et lecture après commit par connexion indépendante selon chaque contrat.

**Alignement AUD-V3-013 :** les protocoles de performance portent sur trois Consumptions READ directes de `POT_EVENT`, **sans** `PreparePotProjectionTasks`, `PotBalanceTask` ou `PotVisibilityTask`. Les mentions résiduelles de « deux tâches », de progression de tâches ou d'acquisition de celles-ci sont historiques et ne créent aucun oracle actif. Mesurer séparément chaque slot/Consumption direct, leur backlog, leurs effets et la complétude des versions. Les budgets C-03 de SPEC-09 (§§4–5) priment : première lecture autorisée complète de W≥V pour Pot actif, observation interne terminale `CurrentPot→PotVersion(P,D)` pour DeletePot, sans `PotBalance(P,D)` ni barrière Visibility dans la lecture directe.

## 2. Matrice unique des exigences

Le tableau relie les exigences actives au protocole détaillé portant le même identifiant ci-dessous. Les scénarios historiques sont conservés lorsqu'applicables et amendés par les décisions normatives. PRJ-04 est **retiré et non réutilisable** ; ses garanties sont migrées dans EVT-03, CON-02/04/06 et PRJ-02/03.

| Exigence | Contrat synthétique (SPEC-07) | Source | Support de preuve | Statut |
|---|---|---|---|---|
| DOM-01 | Pot@V reconstruit à V exacte, indépendamment de V−1 ; sous-versions locales et globale cohérentes | V3-1 Pot | JUnit/PostgreSQL ; Bruno lecture | ACQUIS / À PROUVER |
| DOM-02 | Commandes sans effet, multi-cibles, suppressions et dépendances observedVersion respectent obsolescence métier | V3-1 Pot | Bruno ; JUnit/PostgreSQL | ACQUIS / À PROUVER |
| DOM-03 | Calcul de soldes rationnels exacts, poids par défaut à V ; balance complète par Pot/V | V3-1, V3-3 | JUnit ; Bruno | ACQUIS / À PROUVER |
| ID-01 | Registration/Link/Unlink conservent unicité EUI, identité de la cible, tokens renouvelés, et aucune mutation partielle | V3-1 Identity | Bruno ; JUnit/PostgreSQL | ACQUIS / À PROUVER |
| ID-02 | Link headless avec consentement externe E2 explicite, transactionnel, audience, durée et anti-rejeu | V3-1 Identity | Tests sécurité/Bruno ; JUnit | **ACQUIS / À PROUVER** |
| EVT-01 | Cinq séquences globales denses, publication transactionnelle sans trou ni réordonnancement de commits | V3-2 | JUnit/PostgreSQL concurrent | ACQUIS / À PROUVER |
| EVT-02 | Mutation, événement, résultat terminal et complétion atomiques ; aucune mutation événementielle sans requestId | V3-2 | JUnit/PostgreSQL ; Bruno | ACQUIS / À PROUVER |
| EVT-03 | Sept familles de sources, sans tâches READ ; trois Consumptions autonomes et directes de POT_EVENT, et MaterializeInvitation indépendante | V3-2, V3-4 | JUnit/PostgreSQL | ACQUIS / À PROUVER |
| CON-01 | Discovery enrôle les non-burnables avec checkpoint global inspecté par partition, et les burnables PENDING sans checkpoint | V3-4 | JUnit/PostgreSQL crash/fenêtres | ACQUIS / À PROUVER |
| CON-02 | Checkpoint avancé atomiquement avec tous ses slots ; absence de slot ne vaut complétion que sous ses conditions de preuve | V3-4 | JUnit/PostgreSQL crash/restart | ACQUIS / À PROUVER |
| CON-03 | Propriété de partition autorise Discovery/acquisition ; claim individuel protège le commit, même après transfert de partition | V3-4 | JUnit/PostgreSQL interleavings | ACQUIS / À PROUVER |
| CON-04 | Un claim ne finalise qu'une fois ; validité au verrou final, transaction atomique, reprise sans effet double après commit incertain | V3-4 | JUnit/PostgreSQL fault injection | ACQUIS / À PROUVER |
| CON-05 | Une acquisition par tentative complète ; N échecs techniques durables constatés puis QUARANTINED, réactivation administrative explicite et indépendance des slots | V3-4 | JUnit/PostgreSQL ; k6 contention | ACQUIS / À PROUVER |
| CON-06 | Slots, checkpoints, propriété de partition et effets résident dans les frontières transactionnelles physiques correctes, sans 2PC | V3-4 | Tests d'architecture ; JUnit/PostgreSQL | ACQUIS / À PROUVER |
| PRJ-01 | PotVersion(P,V) immuable, sous-versions exactes, CurrentPot pointeur monotone, y compris version de suppression D | V3-3 | JUnit/PostgreSQL ; Bruno | ACQUIS / À PROUVER |
| PRJ-02 | Balance(P,V) immuable pour chaque V active ; traitement terminal réussi sans Balance(P,D) ; matérialisation hors ordre | V3-3 | JUnit/PostgreSQL | ACQUIS / À PROUVER |
| PRJ-03 | MaterializePotVisibility calcule directement WRITE@V, garde collective PotVisibilityState, navigation monotone, indépendance | V3-3, V3-4 | JUnit/PostgreSQL interleavings | ACQUIS / À PROUVER |
| READ-01 | Lecture Pot complète autorisée à V, aucune barrière de visibilité ; 404 absence/interdit/supprimé avant 503 de fraîcheur/dépendances | V3-3 | Bruno ; JUnit | ACQUIS / À PROUVER |
| SEC-01 | EUI authentifiée + bindingToken et CurrentBinding actif ; droits du créateur ou Shareholders à V ; historique non rétroactivement révoqué ; 404/503 après autorisation | V3-1, V3-3, V3-4 | Bruno sécurité ; JUnit | ACQUIS / À PROUVER |
| SEC-02 | Retard de révocation CurrentBinding accepté explicitement ; aucune promesse de révocation WRITE immédiatement effective dans READ | V3-3, V3-4 | Bruno propagation | ACQUIS / À PROUVER |
| REC-01 | Reconstruction READ en maintenance exclusive, reset coordonné projections/slots/checkpoints sans tâches | V3-4 | JUnit/PostgreSQL E2E | ACQUIS / À PROUVER |
| OPS-01 | Progression et équité malgré quarantaine exclue du softCap, sans dépendance entre slots | V3-4 | k6 ; métriques serveur ; PostgreSQL | **À PROUVER**, budgets V3-5B/C |
| PERF-01 | C-03 : première lecture active W≥V complète autorisée ; DeletePot oracle interne PotVersion(D), budgets inchangés | SPEC-09 | k6 + instrumentation + SQL | **ACQUIS conceptuellement / À PROUVER en V3-7** |
| ADM-01 | Admission bornée, sans lecture métier WRITE, sans slot synchrone | SPEC-06/05 | Bruno ; tests d'admission | ACQUIS / À PROUVER |
| S-01 | Navigation CurrentUserPotList keyset, non autorisante | SPEC-06 | Bruno ; tests de pagination | ACQUIS / À PROUVER |
| S-02 | Manifeste complet et ordonné des références Expenses, détails par lots | SPEC-06/04 | Bruno ; tests de volumes | ACQUIS / À PROUVER |
| S-03 | Idempotence atomique admission par identité et intention canonique | SPEC-06 | JUnit/PostgreSQL ; Bruno | ACQUIS / À PROUVER |

## 3. Domaine

### DOM-01 — Protocole et oracles

**Statut : protocole proposé, non exécuté.** V3-1 définit huit types de composants versionnés (PotHeader/Details, ShareholderHeader/Details/Weight, ExpenseHeader/Details/Financials). Chaque instance locale versionnée s'applique sur l'intervalle semi-ouvert `[activeFrom,activeTo)` exprimé en versions globales du Pot ; `activeTo=null` désigne une borne ouverte. `Pot@V` se reconstruit **directement à V** sans lire `Pot@(V−1)`. Toute relation vers un Shareholder (payer, participant, userId) doit être résolue dans **la même V**.

### Oracle de reconstruction

- Après une mutation effective, la version globale du Pot avance de **exactement un** ; une commande valide sans effet ne l'avance pas. Une mutation multi-cibles n'incrémente V qu'une fois. Les versions locales avancent uniquement pour les composants effectivement modifiés, sans copie des autres.
- Pour chaque composant d'un objet existant à V, il existe **exactement une** instance satisfaisant `activeFrom ≤ V && (activeTo=null || V < activeTo)`. Avant création, aucune instance n'est applicable ; un composant obligatoire absent après création est une anomalie technique.
- Lors d'une modification à V, fermer l'ancienne instance du composant modifié à `activeTo=V` et ouvrir la nouvelle à `activeFrom=V`, version locale précédente +1. Les composants inchangés conservent instance, version et bornes.
- La suppression d'Expense révise uniquement `ExpenseHeader.deleted`, celle du Pot uniquement `PotHeader.deleted` ; Details, Financials, Weight et autres objets survivent historiquement, éventuellement avec `activeTo=null`. Shareholder ne dispose pas de suppression métier.
- Toute référence inter-objets utilise l'identifiant métier stable (`ShareholderId`, `ExpenseId`) et est résolue au V demandé ; jamais la ligne technique d'une révision, la valeur courante WRITE ou une version locale numériquement égale à V.
- Un état historique composé d'intervalles contradictoires (chevauchement, trou après création, référence absente/mauvais Pot) est **signalé**, jamais réparé par un fallback vers le présent ou V−1.

### Cas JUnit / PostgreSQL

| ID | Stimulus | Assertion décisive |
|---|---|---|
| D01-A | Créer Pot V1 | PotHeader et PotDetails en versions locales 1, `activeFrom=1`, `activeTo=null` ; `creatorId` obligatoire |
| D01-B | Créer deux Shareholders en V2, puis une Expense en V3 | Chaque composant des enfants naît à version locale 1 et activeFrom correspondant ; objets absents aux V antérieures |
| D01-C | Changer `ExpenseDetails` en V4 puis `ExpenseFinancials` en V5 | Seul le composant modifié prend une révision ; anciennes bornes fermées exactement à V4/V5 ; autres composantes inchangées |
| D01-D | Changer `ShareholderWeight.defaultWeight` en V6 | Weight révisé seul ; Financials inchangé ; résolution des poids à V5 et V6 distincte ; balance V5 conservée (DOM-03) |
| D01-E | Modifier deux `ShareholderDetails` dans une seule commande V7 | Une seule augmentation globale V ; deux versions locales nouvelles ; aucune autre composante modifiée |
| D01-F | Supprimer Expense V8 puis Pot V9 | ExpenseHeader puis PotHeader seulement révisés en deleted ; enfants et historique encore reconstructibles |
| D01-G | Interroger chacune des versions V1…V9 dans un ordre aléatoire, sans précharger V−1 | États strictement égaux aux snapshots d'un oracle indépendant ; aucune dépendance à la reconstruction précédente |
| D01-H | Résoudre payeur, participants, poids et `ShareholderDetails.userId` après plusieurs révisions | Références et droits pris exclusivement à V demandé, sans fuite des valeurs courantes |
| D01-I | Vérifier limites d'intervalles `V=activeFrom`, `V=activeTo−1`, `V=activeTo` | Sémantique semi-ouverte exacte ; ni double version ni trou |
| D01-J | Injecter trou, chevauchement d'intervalles, composant obligatoire absent ou référence vers un autre Pot dans fixture isolée | Anomalie détectée explicitement ; reconstruction refusée plutôt que complétée artificiellement |
| D01-K | Commande valide sans effet puis mutation multi-cibles puis rollback forcé | Aucune version pour no-op ou rollback ; exactement une V globale et seules sous-versions changées pour multi-cibles |
| D01-L | Comparer reconstru­ction directe d'une V ancienne après de nombreuses mutations et suppressions | Digest canonique du Pot@V inchangé, indépendamment de l'ordre d'accès et des révisions ultérieures |

### Méthode et PASS

Construire les snapshots attendus en mémoire **indépendamment** des sélecteurs SQL/algorithmes de reconstruction testés. Exécuter JUnit contre PostgreSQL réel pour les intervalles, FK, frontières transactionnelles et relations ; compléter avec tests unitaires pour la composition pure. Capturer pour chaque V : snapshot attendu, lignes et intervalles en base, versions locales, identifiants métier et digest canonique. La consultation historique ici est un **oracle de domaine interne**, pas une exigence d'API READ historique arbitraire. Les règles de dépendance métier `observedVersion` seront éprouvées en DOM-02, les fractions et balances en DOM-03.

**PASS DOM-01** : D01-A à D01-L satisfaits, reconstruction sans chaînage à V−1, versions locales indépendantes, intervalles non ambigus, suppressions historisées et absence de repli silencieux en présence d'incohérence. Aucun code/test V3 exécuté.

### DOM-02 — Protocole et oracles

**Statut : protocole proposé, non exécuté.** `observedVersion` est la version globale initialement vue par l'auteur : elle reste **immuable à travers toutes les tentatives**. Chaque tentative repart de la version globale courante, recharge les données autoritatives et contrôle les sous-composants métier dont la commande dépend. Une perte du CAS global est un **conflit technique transitoire** et ne constitue jamais, à elle seule, une obsolescence métier. Une obsolescence établie sur les dépendances à la version courante donne lieu à un **rejet métier durable** sans mutation. Une commande valide sans changement réussit sans nouvelle version métier, mais avec complétion.

### Oracle des dépendances

| Commande | Dépendances dont le changement depuis observedVersion rend obsolète | Mutation attendue si effet |
|---|---|---|
| Modifier PotDetails | PotDetails | PotDetails |
| Ajouter Shareholder | Pas de sous-composante antérieure cible ; vérifier Pot actif et références utiles | Header, Details, Weight nouveaux à v1 |
| Modifier ShareholderDetails | ShareholderDetails | ShareholderDetails |
| Modifier ShareholderWeight | ShareholderWeight | ShareholderWeight |
| Créer Expense | Pas de sous-composante antérieure cible ; vérifier Pot actif et validité des Shareholders | Header, Details, Financials nouveaux à v1 |
| Modifier ExpenseDetails | ExpenseDetails | ExpenseDetails |
| Modifier ExpenseFinancials | ExpenseFinancials | ExpenseFinancials |
| Supprimer Expense | ExpenseHeader, ExpenseDetails, ExpenseFinancials | ExpenseHeader.deleted |
| Supprimer Pot | **Toute version globale intermédiaire** : `observedVersion == currentPotVersion` obligatoire | PotHeader.deleted |

Une composante existant au moment de l'observation reste compatible si son instance actuelle possède `activeFrom ≤ observedVersion` ; ce contrôle ne dispense jamais de vérifier existence, appartenance au Pot, statut actif des Headers, références et autorisations **au moment de l'exécution**. Les révisions indépendantes n'annulent pas automatiquement une commande compatible. Le moteur ne réécrit jamais `observedVersion` lors d'un retry.

### Cas déterministes

| ID | Stimulus / entrelacement | Oracle de réussite |
|---|---|---|
| D02-A | Changement PotDetails entre observation et exécution d'une modification PotDetails | Rejet métier terminal ; aucune nouvelle V ni sous-version ; complétion de rejet |
| D02-B | Changement ShareholderWeight après observation d'une commande visant ShareholderDetails (et cas inverse) | Les deux mutations peuvent aboutir avec des versions globales successives ; seule la composante cible est reversionnée |
| D02-C | Changement ExpenseDetails ou Financials avant suppression d'Expense observée antérieurement | Suppression rejetée ; aucune nouvelle mutation de Header ; un changement de Weight seul ne la rend pas obsolète |
| D02-D | Modification d'ExpenseFinancials concurrente d'un changement de ShareholderWeight | Si références et dépendances sont encore valides, les deux opérations réussissent ; balance issue des poids et Financials applicables à V |
| D02-E | Supprimer Pot à observedVersion ancienne après **n'importe quelle** mutation effective intermédiaire | Rejet ; à version globale égale et Pot actif, suppression admise ; aucune réécriture des enfants |
| D02-F | Ajout Shareholder ou Expense préparé depuis ancienne V alors que des composants sans lien ont évolué | Autoriser si Pot et références métier courantes restent valides ; aucune exigence artificielle d'égalité globale |
| D02-G | Commande multi-cibles dont une cible est devenue obsolète | Rejet global atomique, aucune autre cible modifiée, aucune nouvelle V |
| D02-H | Commande multi-cibles valide touchant plusieurs composantes | Une seule V globale supplémentaire, versions locales incrémentées uniquement sur les composantes effectivement changées |
| D02-I | Commande valide qui demande la valeur déjà présente et ne dépend d'aucune composante obsolète | Succès sans effet, aucune nouvelle V/événement de mutation ; résultat/complétion uniques |
| D02-J | Commande observée obsolète mais demandant une valeur égale à la valeur actuelle | **Rejet métier**, même si la nouvelle valeur paraît identique ; l'égalité de valeur ne supprime pas l'obsolescence |
| D02-K | Deux commandes préparent sur même version de travail, l'une gagne le CAS global et l'autre perd | Perdante rollback technique, **pas** rejet métier automatique ; claim cédé avec retryAfter selon CON-05 |
| D02-L | Après perte du CAS, la tentative suivante recharge V courante ; les dépendances initiales restent compatibles | Nouvelle tentative avec **même observedVersion**, puis succès et V suivante ; aucun double effet |
| D02-M | Après perte du CAS, la tentative suivante découvre qu'une dépendance métier a réellement changé | Rejet métier terminal décidé après rechargement ; pas d'écriture partielle ni retry indéfini |
| D02-N | Expense/Shareholder supprimé ou Pot supprimé entre observation et exécution | Vérification des Headers actifs et des références courantes ; refus conforme même si la composante directement visée n'a pas changé |
| D02-O | Fault injection entre vérification des dépendances et commit final, avec autre mutation concurrente | CAS/contrôles transactionnels empêchent toute validation sur version de travail obsolète ; retry complet ou rejet métier explicite, jamais état incohérent |
| D02-P | Deux requêtes équivalentes avec requestIds différents, puis retry technique d'un seul requestId | Chaque demande reçoit son propre résultat ; retry n'applique jamais deux fois **la même demande** ; ne pas confondre idempotence de demande et égalité des intentions |

### Preuves et critères PASS

JUnit avec **PostgreSQL réel**, barrières déterministes pour la préparation, la perte CAS, la réacquisition et la finalisation ; Bruno pour l'issue externe et `resultingPotVersion`. Capturer la version observée **avant** la première tentative, la version de travail de **chaque** tentative, les révisions locales, les événements et résultats/complétions durables, ainsi que les slots/claims de retry. Les états attendus doivent être calculés par un oracle indépendant des conditions SQL testées.

Recoupements obligatoires avec DOM-01 (intervalles), DOM-03 (soldes), EVT-02 (rejet/complétion atomique), CON-04/05 (fencing/retry) et SEC-01 (identité/autorisation). Ne pas inférer l'obsolescence d'une simple inégalité `observedVersion != currentVersion`, sauf pour la suppression totale du Pot où cette égalité est explicitement exigée.

**PASS DOM-02** : D02-A à D02-P démontrés ; validité fondée sur les dépendances métier, unchanged observedVersion, nouvelle tentative entière après perte CAS, absence de commit partiel et de faux rejet. Aucun test V3 n'a été implémenté ni exécuté.

### DOM-03 — Protocole et oracles

**Statut : protocole proposé, non exécuté.** Règle métier V3-1 : pour toute Expense **active** à la version globale V, le payeur reçoit un crédit du montant ; chaque participant reçoit un débit proportionnel à son poids effectif à V, défini par `specificWeight` de l'Expense s'il existe, sinon `ShareholderWeight.defaultWeight` applicable à V. Le payeur n'est pas nécessairement participant. Les montants sont non négatifs ; poids spécifiques et par défaut strictement positifs ; la liste de participants est non vide. Les fractions sont représentées/calculées exactement, avec normalisation, sans flottants d'approximation. La somme des soldes est exactement nulle.

### Oracle indépendant et règles

- Pour chaque Expense active e à V, calculer indépendamment `w_i(V)`, `W(V)=Σ_i w_i(V)`, `part_i(e,V)=amount(e,V) × w_i(V)/W(V)` ; contribuer `+amount` au payeur et `−part_i` à chacun des participants. Le solde de chaque Shareholder est la somme des contributions rationnelles de toutes les Expenses actives de V. `Σ soldes = 0` **exactement**, y compris si payeur et participant sont identiques.
- Les `ShareholderId` des payeurs/participants doivent appartenir au Pot et être valides selon son état à V. Un `specificWeight` explicite ne dépend pas des révisions de `defaultWeight` ; une participation sans poids explicite suit le poids par défaut à **la version globale concernée**, sans modifier `ExpenseFinancials`.
- La révision d'un poids par défaut produit une nouvelle version globale du Pot et recalcule **toutes** les balances de cette version ; elle ne change pas les poids spécifiques, les participants, les Expenses financières ou les balances historiques précédentes.
- Une Expense supprimée contribue jusqu'à la dernière version précédant sa suppression, puis ne contribue plus ; ses composants historiques sont conservés. Une suppression de Pot conserve les balances historiques et les valeurs nécessaires à leur reconstruction, même si la consultation fonctionnelle courante du Pot supprimé est régie par son état.
- Le jeu de résultats doit inclure **chaque Shareholder attendu**, y compris ceux de solde nul conformément au contrat de balance complète ; comparer les clés et fractions normalisées, pas seulement la somme globale.

### Cas déterministes

| ID | Stimulus / fixture | Oracle de réussite |
|---|---|---|
| D03-A | Expense de 100, payeur A, participants A/B de poids 1 et 3 | Crédit A +100 ; débits A −25 et B −75 ; soldes nets A +75, B −75, total exactement zéro |
| D03-B | Payeur A non participant, bénéficiaires B/C à poids 1/2 | A reçoit tout le crédit ; B/C portent les fractions exactes du débit ; somme zéro |
| D03-C | Montants et poids conduisant à des tiers/septièmes, avec multiples Expenses | Fractions exactes réduites ; aucune dérive flottante ni arrondi cumulatif |
| D03-D | Participant à specificWeight et autre au defaultWeight, puis changer seulement le defaultWeight | Seules les parts dépendant du défaut évoluent ; ExpenseFinancials inchangé ; balances historiques intactes |
| D03-E | Tous les participants ont specificWeight ; réviser leur defaultWeight | Répartition de cette Expense inchangée, mais **nouvelle balance complète** à la nouvelle V globale |
| D03-F | Modifier amount et payerId d'une Expense, avec références valides | Solde recalculé sur les Financials exacts de la nouvelle V ; ancien résultat conservé |
| D03-G | Modifier le libellé ou la date sans modifier Financials | Même valeur numérique attendue à la nouvelle V, stockée comme balance indépendante |
| D03-H | Supprimer une Expense après plusieurs révisions, puis reconstruire ses V précédentes | Contribution supprimée uniquement à partir de la V de suppression ; histoire correcte |
| D03-I | Plusieurs Shareholders d'un Pot dont certains ne paient ni ne participent | Aucun Shareholder attendu oublié ; zéros explicitement traités conformément à la représentation canonique |
| D03-J | Montant zéro avec participants et poids valides | Tous les soldes exactement nuls, sans division illégale ni version artificielle |
| D03-K | Montant négatif, poids nul/négatif, participants vides, payeur hors Pot, références invalides | Entrée rejetée selon les invariants métier ; aucune mutation partielle ni balance incomplète |
| D03-L | Mutation multi-cibles mêlant poids et Financials | Une seule nouvelle V globale ; calcul sur l'état composé après mutation, non mélange avant/après |
| D03-M | Recalculer V ancienne après changement de poids et suppression à V+N | Résultat strictement identique à celui calculé initialement pour V |
| D03-N | Traitement Balance V+1 avant V, retry et duplication | Balances exactes, immuables et indépendantes ; aucune dépendance à une balance précédente (PRJ-02) |
| D03-O | Petits, moyens et grands Pots ; montants/poids rationnels de tailles diverses | Vérifier la conservation et l'exactitude sur cas générés reproductibles ; documenter coûts sans inférer un PASS de performance |

### Méthode et PASS

Tests unitaires du calcul pur avec **oracle indépendant** fondé sur des entiers/fractions exactes, puis JUnit/PostgreSQL réel pour les versions de poids, la reconstruction `Pot@V`, la publication et les balances immuables. Cas tabulaires déterministes complétés par cas génératifs à seed fixe ; conserver données, parts attendues et calcul des sommes. Comparer les valeurs **pour chaque Shareholder** à chaque V. Croiser DOM-01 (reconstruction), DOM-02 (mutations/versions), PRJ-02 (matérialisation immuable) et EVT-02 (rollback). Les objectifs de 500 ms/2 s restent évalués uniquement dans PERF-01.

**PASS DOM-03** exige D03-A à D03-O, conservation rationnelle exacte, poids par défaut appliqués à V, validations négatives et absence de modification des résultats historiques. Aucun test V3 n'a été implémenté ni exécuté.

## 4. Identity

### ID-01 — Protocole et oracles

**Statut : protocole proposé, non exécuté.** Contrats V3-1 Identity et V3-5A : `PocomaUser` existe indépendamment des EUI ; une EUI unique par couple opaque `(issuer,subject)`, avec identifiant interne stable et histoire locale `version` commençant à 1, incrémentée de 1 **uniquement** lors d'une transition effective. Le rattachement `pocomaUserId?` et `bindingToken?` sont simultanément présents ou absents. `bindingToken` est neuf à chaque rattachement et nul après Unlink ; aucune entité Binding autonome n'est créée.

### Oracles métier et transactionnels

- **Registration :** demande initiée par une EUI authentifiée non liée, inconnue ou existante libre. Une réussite crée **exactement un nouveau PocomaUser**, puis crée EUI directement liée v1 ou incrémente sa version si elle existe libre. Aucune EUI liée à un User existant n'est réenregistrée.
- **Link :** E1 authentifiée et encore liée à U, E2 libre ou inconnue et consentante (les preuves d'admission de E2 sont spécifiées en ID-02). Le User cible est **déduit de E1**, jamais fourni librement ; E2 devient liée à ce User avec **un nouveau token**. Si E2 est déjà liée, même à ce User, la demande est rejetée.
- **Unlink :** E1 encore liée à U (et présentant son propre `bindingToken` actif), E2 encore liée au **même U** (sans exiger que E1 connaisse le `bindingToken` secret de E2 lorsque `E1 != E2`). Il peut s'agir de E1 elle-même, de la dernière EUI liée à U, ou d'une autre EUI de U. Unlink rend User et token de E2 nuls, en nouvelle version ; PocomaUser persiste.
- **Unicité/historique :** le couple `(issuer,subject)` ne peut pas être dupliqué même après Unlink ; une EUI liée U1 peut passer à U2 **uniquement via Unlink puis Link** ; ses versions sont reconstructibles directement. Des `subject` égaux avec `issuer` distincts sont deux EUI distinctes.
- **Atomicité :** mutation du User/EUI, versions historiques, événements Identity correspondants, résultat, complétion et slot de la demande partagent la transaction WRITE (EVT-02/CON-06). Aucun User orphelin créé par une Registration perdante ; ni état `pocomaUserId` non nul avec token nul, ni l'inverse.
- **Concurrence :** les vérifications de l'autorité initiatrice E1 et de l'état de E2 restent valides **jusqu'au commit** ; verrouillage exclusif des EUI concernées dans l'ordre lexicographique canonique `(issuer, subject)` (SPEC-05 §4). Un conflit technique de concurrence se résout selon CON-05, tandis qu'un rejet métier constaté est terminal.

### Scénarios JUnit/PostgreSQL et Bruno

| ID | Stimulus / entrelacement | Oracle de réussite |
|---|---|---|
| I01-A | Registration d'une EUI inconnue | Un seul nouveau User, EUI créée directement liée en v1, token neuf, événements User v1 et EUI v1, complétion unique |
| I01-B | Registration d'une EUI existante libre à vN | Nouveau User, EUI liée à vN+1, identité interne et EUI opaque inchangées |
| I01-C | Registration d'une EUI déjà liée | Rejet sans nouvelle version, événement de mutation ni nouveau User ; complétion de rejet |
| I01-D | Deux Registrations concurrentes sur une EUI inconnue, puis sur une EUI libre | Un seul gagnant dans chaque cas ; aucun User orphelin ; unicité SQL vérifiée sous course |
| I01-E | Link E1→E2 inconnue, puis E2 libre existante | E2 liée au User déduit de E1, directement v1 ou vN+1 ; token neuf ; aucun User créé |
| I01-F | Deux Links concurrents visant la même E2, et Registration/Link concurrentes | Une transition gagnante, autres rejets sans mutation ; aucun User orphelin ni double liaison |
| I01-G | Link sur une E2 déjà liée, y compris au même User | Rejet strict ; aucune nouvelle version et aucun token renouvelé |
| I01-H | Unlink de E2 par E1 du même User, puis Unlink de E1 elle-même / dernière EUI | E2 devient libre avec token nul à version suivante ; PocomaUser persiste même sans EUI |
| I01-I | Unlink avec `bindingToken` initiateur E1 obsolète, E2 déjà libre, ou E2 liée à un autre User | Rejet, zéro mutation EUI, aucun nouveau token |
| I01-J | Deux Unlinks croisés E1→E2 et E2→E1 avec barrières concurrentes | Au plus un succès : la seconde perd sa qualité d'initiatrice valide ; absence de deadlock persistant |
| I01-K | Link concurrent d'Unlink de E1 initiatrice | Link ne peut valider sur E1 déjà détachée ; vérification initiatrice et écriture E2 sérialisées jusqu'au commit |
| I01-L | Historique E2 : liée U1/token B → libre → liée U2/token C → libre → liée U1/token D | Versions contiguës, UserId/tokens exacts à chaque version ; B, C, D distincts et anciens tokens non acceptés |
| I01-M | Deux identités ayant même `subject` mais `issuer` distinct, et tentative de duplication du couple exact | Distinction par couple complet, unicité globale du couple et stabilité de externalUserIdentityId |
| I01-N | Exception après création du User, après modification EUI ou après un premier événement Identity, avant commit | Rollback intégral des mutations, de l'historique, des événements, du résultat et du slot ; aucun User orphelin |
| I01-O | Commit réussi mais réponse perdue, puis même requestId réexécuté | Résultat initial retrouvé, aucune seconde transition, aucun nouveau token ni User |
| I01-P | Rejeu de la même intention sous un **nouveau requestId** après succès | Application des préconditions actuelles : Link/Registration strictement refusés si EUI liée ; ne pas confondre avec retry idempotent |
| I01-Q | Contrôle d'accès au token courant après Link/Unlink via READ en retard puis rattrapé | Token exposé uniquement à l'EUI authentifiée autorisée ; propagation conforme à CurrentBinding et SEC-01/02 |

### Preuves, périmètre et PASS

JUnit sur **PostgreSQL réel** avec contraintes d'unicité, connexions et barrières transactionnelles pour les conflits, fault injection avant commit, comparaison des versions exactes et des cardinalités User/EUI/événements ; Bruno pour les issues API et témoins externes. Les tokens de test sont comparés dans des assertions privées sans être inscrits dans logs, exports de métriques ni rapports publics. Les valeurs de tokens sont imprévisibles et uniques par transition ; une simple différence sur quelques fixtures ne constitue pas, seule, une preuve de qualité de génération aléatoire : inspection du générateur et tests de son contrat sont également requis.

**Séparation des responsabilités :** ID-01 couvre les effets et préconditions d'Identity ; ID-02 démontre l'authentification et le consentement E2 à l'admission, ainsi que la portée temporelle des entitlements. EVT-02 et CON-06 couvrent le commit physique, tandis que SEC-01/02 couvrent l'accès READ et sa propagation.

**PASS ID-01** : I01-A à I01-Q satisfaits, aucune double EUI, aucun User orphelin, tokens renouvelés, transitions strictes/histoire exacte et atomicité sous concurrence. Aucun test V3 implémenté ni exécuté.

### ID-02 — Protocole et oracles

**Statut : protocole proposé, non exécuté.** Arbitrages V3-5A : un Link exige que **E1 et E2 soient toutes deux authentifiées** et que **E2 consente explicitement à la demande de Link particulière**, avant son admission durable. La vérification des attestations/entitlements du fournisseur externe est effectuée **à l'admission seulement**, sans redemande à l'exécution ou au retry. L'exécution vérifie en revanche les **préconditions courantes de Pocoma WRITE** (E1 toujours liée avec son token courant à U, E2 libre ou inconnue, unicité, correspondance des EUI, autorité et invariants). Pas de mécanisme de consentement différé, d'objet Consent autonome, ni de domaine Entitlements chez Pocoma.

### Contrat probatoire et frontières de confiance

- **Admission :** établir deux identités authentifiées distinctement par `(issuer,subject)` (elles peuvent provenir du même fournisseur ou de fournisseurs différents), puis lier cryptographiquement ou via une session serveur fiable le consentement E2 à l'**intention exacte** qui deviendra la demande durable : identité E1, identité E2, nature de l'opération, cible liée/portée pertinente, identifiant ou empreinte canonique non substituable de la demande, et fraîcheur/anti-rejeu nécessaires. Les champs exacts et le mode de signature/session sont à arrêter dans le contrat d'admission V3 avant V3-CADRE : aucune simple déclaration client de `subject` ne suffit.
- **Consentement :** E2 ne choisit pas librement `pocomaUserId` ; la cible U est résolue de E1 à l'exécution. Le consentement est à l'opération précise, non une délégation ouverte « E1 peut rattacher n'importe quelle identité ». Un consentement usurpé, modifié, réutilisé pour une autre demande, expiré à l'admission ou retiré avant admission selon le protocole retenu ne doit pas créer de demande durable.
- **Admission durable :** persister l'intention immuable et les preuves/attestations nécessaires avec le `requestId` avant d'accuser réception. Une requête ne doit pas apparaître comme admise si la preuve E2 ou les entitlements externes exigibles sont absents/invalides.
- **Exécution :** ne pas réinterroger le fournisseur pour les entitlements externes ni redemander l'accord de E2 ; la preuve d'admission déjà validée reste attachée à la demande. En revanche les tokens, liens et préconditions Identity **courants en WRITE** peuvent rendre la demande obsolète/rejetée. La révocation externe post-admission n'annule pas rétrospectivement une demande admise.
- **Séparation sécurité :** authentification des EUI, preuve de consentement et `bindingToken` sont trois éléments différents. Un bindingToken connu ne prouve ni l'identité de E2 ni son consentement. Aucun `PocomaUserId` fourni arbitrairement par le client ne constitue une autorité.
- **Audit protégé :** prouver la validité/traçabilité de l'admission sans divulguer tokens, secrets de session ni attestations complètes dans logs ou métriques. Le protocole ne prescrit pas de nouveau stockage métier autonome : les données utiles restent dans la demande Identity admise.

### Scénarios de preuve

| ID | Stimulus / entrelacement | Oracle de réussite |
|---|---|---|
| I02-A | E1 et E2 authentifiées, consentement E2 valide et lié exactement à Link E1→E2 | Une seule IdentityRequest durable admise avec ses attestations ; exécution possible selon l'état WRITE |
| I02-B | E1 authentifiée, mais aucune authentification prouvée de E2 | Admission refusée, aucune IdentityRequest durable |
| I02-C | E2 authentifiée, mais consentement explicite absent ou refusé | Admission refusée ; aucun Link effectué ultérieurement |
| I02-D | Client substitue `issuer`, `subject`, E1, E2 ou la portée après consentement | Preuve invalidée ; impossible de faire porter l'accord sur une autre demande |
| I02-E | Preuve de consentement capturée pour demande A réutilisée pour demande B | Rejeu refusé ; aucun contournement par nouvel `requestId` ou payload équivalent non autorisé |
| I02-F | Consentement expiré, signature falsifiée, session détournée ou preuve émise par mauvais émetteur | Admission refusée ; aucun succès fondé sur la simple connaissance d'identifiants |
| I02-G | Entitlement externe exigé manquant, invalide ou non attesté à l'admission | Refus d'admission avant persistance, preuve d'absence de demande durable |
| I02-H | Entitlements valides à l'admission, puis révoqués côté fournisseur avant l'exécution | La demande admise conserve sa validité externe ; aucune nouvelle vérification fournisseur au Worker ; décision sur autorité Pocoma courante |
| I02-I | E1 est Unlink après admission de Link mais avant exécution | Link rejeté à l'exécution si E1 n'a plus son rattachement/token autoritatif ; aucun rattachement E2 ni effet partiel |
| I02-J | E2 est Registration/Link par une autre demande après admission, avant exécution | Première transition validée gagne ; Link tardif rejeté sans modifier E2 et sans redemander le consentement |
| I02-K | Exception technique, perte CAS/claim, expiration puis réacquisition de la même demande durable | Intention et consentement initiaux conservés ; aucune nouvelle sollicitation de E2 ni relecture des entitlements externes ; réévaluation complète des faits Pocoma |
| I02-L | Réponse d'admission perdue puis retransmission avec même clé/idempotence de demande | Au plus une demande durable correspondant à l'intention autorisée ; aucun consentement transformé en autorisation d'une nouvelle demande |
| I02-M | E1 fournit un `pocomaUserId` arbitraire ou un `bindingToken` de E2 en prétendant disposer de son accord | Admission/exécution n'utilise pas ces déclarations pour établir le contrôle E2 ; User cible déduit de E1 WRITE |
| I02-N | Deux Link concurrents avec preuves d'admission distinctes visant la même E2 | Les deux demandes peuvent être valablement admises, mais une seule mutation EUI gagne à l'exécution ; l'autre rejetée sans effet |
| I02-O | Parcours Bruno avec Keycloak réel : deux sessions/jetons E1 et E2, consentement lié à la demande exacte | Authentifications vérifiées, mauvais issuer/audience/expiration refusés ; aucune dépendance à des jetons non vérifiés |
| I02-P | Comparer erreurs pré-admission aux rejets métier terminaux post-admission | Admission refusée ⇒ aucune demande ; demande admise devenue invalide WRITE ⇒ résultat terminal de rejet et completion, sans mutation |
| I02-Q | Revue de l'intégrité des preuves persistées, contrôles d'accès et journaux | Impossible de substituer consentement/attestation au Worker ; aucun secret ou token sensible exposé en logs, traces ou rapports |

### Méthode, inconnues à trancher et PASS

Utiliser **Bruno/Keycloak** pour les authentifications indépendantes et attaques de substitution/rejeu ; **JUnit/PostgreSQL réel** pour l'admission atomique, la persistance des preuves, les conflits et la réévaluation à l'exécution ; injections déterministes pour les races et retries. Les tests doivent comprendre une implémentation de preuve négative réellement falsifiée, et pas seulement des mocks acceptant/refusant à volonté. Contrôler la base depuis une connexion indépendante pour distinguer refus pré-admission (absence d'IdentityRequest) et rejet terminal après admission.

**Décision préalable à V3-CADRE encore nécessaire :** fixer la forme du consentement E2 (deux authentifications dans un échange serveur fiable, attestation signée à l'intention exacte ou autre contrat vérifiable), ses bornes de fraîcheur, anti-rejeu et sa liaison à l'IdentityRequest sans adopter un nouveau workflow durable. Ce choix technique doit permettre les assertions ci-dessus, mais **aucune solution n'est déclarée implicitement validée**.

**PASS ID-02** : I02-A à I02-Q réussis, absence de Link sans contrôle/consentement E2 valide, vérification externe exclusivement à l'admission et autorité Pocoma recontrôlée à l'exécution, reprise sans nouveau consentement. Aucun test V3 n'a été écrit ni exécuté.

**Amendement normatif supersédant les champs des §§32–37 relatifs à `nombre indicatif de Pots visibles` et à la nécessité d'une même autorité pour identité et consentement :** le récapitulatif comporte l'identité E1 authentifiée, l'action de rattacher E2 au compte associé à E1, le nombre **indicatif de Pots accessibles** obtenu depuis `CurrentUserPotList` par résolution du Binding E1, et la date de consultation des projections. Aucun compteur d'EUI, aucune fraîcheur minimale, aucun index Identity inverse. Le nombre de Pots n'est pas un contrôle d'autorisation ni une condition de validité du Link ; aucun état instantané n'est certifié. Les contrôles de E1 et de E2 restent indépendants.

**Rôles :** fournisseur d'identité authentifiant E2 ; serveur d'autorisation présentant le Link exact à E2 et recueillant un accord explicite, avec attestation vérifiable destinée à Pocoma. Produit commun ou distinct permis, Pocoma headless. RFC 9396 RAR privilégié mais non imposé. Keycloak candidat, non qualifié par hypothèse ; adaptation, autre fournisseur ou composant dédié possibles. La réalisation et la qualification du fournisseur demeurent des preuves V3-6/V3-7, pas une autorisation de réduire C-01.

**Scénarios à certifier :** E1 authentifiée et Binding de E1 actif ; compteur de Pots incluant le créateur visible et dédoublonnant les entrées Pot ; READ en retard et variation du compteur entre présentation et exécution sans rejet injustifié ; aucune requête `CurrentBinding` par User ni divulgation du `PocomaUserId` ; présentation effective de l'identité initiatrice et de l'action Link ; E2 authentifiée puis accord explicite, refus/SSO silencieux/ID Token seul non recevables ; altération E1, E2, intention, destinataire, audience, résumé présenté, signature ou échéance refusée ; deux retours simultanés ou rejeu d'attestation donnant au plus une admission ; revalidation de WRITE à l'exécution et rejet si E1 ou E2 ne satisfait plus ses préconditions. Tester avec un serveur d'autorisation réellement qualifié, qu'il soit Keycloak ou non. Aucun test V3 exécuté.

## 5. Événements

### EVT-01 — Protocole et oracles

**Statut : protocole proposé, non exécuté.** Cinq séquences indépendantes : `POT`, `INVITATION`, `IDENTITY`, `COMMAND_COMPLETION`, `IDENTITY_COMPLETION`. Ces positions ne sont ni des versions métier ni un ordre total entre familles.

### Préconditions et oracle

- PostgreSQL réel ; transactions indépendantes, au moins deux connexions et deux threads distincts, barrières explicites aux frontières d'attribution, de commit et de rollback ; **aucun sleep ne sert de preuve d'entrelacement**.
- Initialiser chaque famille à un préfixe durable `1…N`; capturer le journal des événements committés et l'issue physique des transactions. Ne pas tirer l'ordre des commits d'horodatages applicatifs.
- Pour chaque famille, **sur le jeu de données dédié au test**, les positions committées, ordonnées, sont exactement `1…M`, sans trou ni doublon ; chaque événement conserve son eventId, son sujet et sa position. Répéter le test sur bases préfixées permet de vérifier la continuation, pas seulement le départ à 1.
- Quand T1 a obtenu une position inférieure à celle demandée par T2 sur **la même famille** et que T1 est physiquement non résolue, T2 ne peut committer sa position supérieure avant la résolution de T1. Tester cela par barrières et observation des états transactionnels, sans confondre ordre de démarrage et ordre de commit.
- **Aucune exigence d'ordre entre familles** : les compteurs et validations sont indépendants ; une transaction peut publier dans la famille de mutation puis dans la famille de complétion, dans une seule transaction physique.

### Cas déterministes

| ID | Entrelacement contrôlé | Assertion décisive |
|---|---|---|
| E01-A | Une transaction publie un événement et commit, puis une autre publie | Positions successives, uniques, stables et retrouvables |
| E01-B | T1 obtient position `n+1` et reste ouverte ; T2 tente de publier sur la même famille | T2 n'obtient pas une position supérieure **committable** pendant que T1 est indécise ; après commit T1, T2 peut committer `n+2` |
| E01-C | Même scénario, mais T1 rollback ; T2 continue | T2 obtient `n+1`, pas `n+2` ; aucune position consommée par l'abandon |
| E01-D | T1 réserve un lot de deux événements Identity dans sa transaction ; T2 publie en concurrence | Les deux positions du lot sont consécutives et l'ordre interne déclaré est conservé ; pas d'intercalation |
| E01-E | Command sans effet/rejet et demande Identity rejetée | Seule la famille de complétion avance ; aucune position de mutation consommée |
| E01-F | Publication de mutation et complétion, puis rollback forcé avant commit | Aucun événement des deux familles durable, et aucun compteur avancé ; terminalisation/résultat absents (recoupement EVT-02) |
| E01-G | Multiples transactions mixtes commit/rollback sur les quatre familles, ordre d'ouverture intercalé | Chacune des quatre suites demeure dense indépendamment ; les résultats reflètent uniquement les commits |
| E01-H | Charge concurrente répétée, transactions lentes, aborts, redémarrage de connexions | Même invariant de densité ; aucune perte, doublon, inversion dans une même famille, ni attente bloquée inexpliquée |

### Garde-fous sur l'interprétation

- Ne jamais accepter un test qui construit les positions avec `nextval()` PostgreSQL non transactionnel : les rollbacks y laissent des trous. Un test vérifie le **comportement public/durable**, pas une implémentation de compteur prescrite prématurément.
- L'affirmation « ordre des commits » est **intra-famille** et sur les publications participant au séquencement transactionnel ; il n'existe pas d'ordre inter-familles à certifier.
- Ne pas inférer un ordre physique de commit depuis `recordedAt`, un compteur local du test, un `sleep`, ou l'instant où un thread reçoit sa réponse. Les barrières doivent rendre la dépendance visible, et les assertions SQL vérifier l'état **après** résolution.
- Cas de deadlock/protocole : établir qu'après prise des verrous de séquence le chemin n'acquiert plus de nouveaux verrous métier ; couvrir les deux paires d'ordres prévues `POT → COMMAND_COMPLETION` et `IDENTITY → IDENTITY_COMPLETION` par des scénarios de contention et analyse du graphe d'acquisition (liens avec CON-06).
- Isoler l'éventuelle attente de verrou des budgets de performance de V3-5C et la mesurer ; aucun blocage infini toléré.

### Preuves à conserver et critère PASS

Données de départ et SQL de vérification de densité par famille, journal de l'ordonnanceur de test/barrières et des issues transactionnelles, assertions du nombre et de l'identité des événements, résultats de répétitions, trace des éventuels rollbacks et blocages. **PASS EVT-01** seulement si A–H passent sur PostgreSQL réel et si la protection d'ordre de verrous est vérifiée ; aucune implémentation ou test n'est réalisé à ce stade.

### EVT-02 — Protocole et oracles

**Statut : protocole proposé, non exécuté.** Périmètre : transaction terminale d'une `COMMAND` ou `IDENTITY_REQUEST`. Une demande ne devient `TERMINAL` qu'avec exactement un résultat durable et un événement de complétion ; ses mutations et événements éventuels sont dans **la même transaction physique**. Un rejet ou succès sans effet ne publie aucun événement de mutation. Une erreur technique avant commit ne doit pas être transformée en rejet terminal.

### Oracle commun et photographie de référence

Avant chaque scénario, relever un état cohérent du domaine, la demande `PENDING`, l'absence de résultat terminal et les positions/événements des familles concernées. Après résolution de la transaction, interroger PostgreSQL depuis **une connexion indépendante** pour comparer : état métier et versions, statut de demande, résultat terminal, événement(s) de mutation, événement de complétion, positions des séquences et slot de Consumption.

- **Commit d'une mutation :** toutes les modifications métier attendues, leurs événements portant le `requestId` exact, un résultat unique, la demande `TERMINAL`, l'événement de complétion unique et la suppression du slot de traitement apparaissent ensemble ; aucun événement supplémentaire.
- **Commit sans mutation (no-op ou rejet) :** état métier et versions inchangés, aucun événement de mutation ; demande `TERMINAL`, résultat approprié et une complétion durable. Un rejet ne masque jamais un échec technique.
- **Rollback :** état métier, versions, événements, positions de séquence, résultat, statut `PENDING` et slot durable restent dans l'état antérieur à la tentative ; le claim peut ensuite être relâché/expirer conformément à CON-05, **sans** finalisation durable.
- **Commit incertain côté Worker :** décider par lecture durable de l'état, jamais par répétition aveugle. Si `TERMINAL` et slot absent : constater l'unique effet ; si `PENDING` et slot présent : reprise comme nouvelle acquisition. Une réponse perdue ne suffit pas à conclure au rollback.

### Cas déterministes

| ID | Stimulus et frontière de panne | Assertions attendues |
|---|---|---|
| E02-A | `COMMAND` créant/modifiant un Pot (y compris multi-cibles) ; commit | Une seule nouvelle version globale et un `POT_EVENT` correspondant ; résultat, `COMMAND_COMPLETION_EVENT`, terminalisation et slot cohérents dans le même commit |
| E02-B | `COMMAND` réussie sans effet | Aucune nouvelle version/événement Pot ; résultat succès et complétion uniques, atomiques |
| E02-C | `COMMAND` rejetée par obsolescence métier | Aucune mutation/événement Pot ; résultat rejet métier et complétion uniques ; tentative technique non assimilée à rejet |
| E02-D | `Registration` gagnante | Création `PocomaUser` et rattachement EUI, **deux `IDENTITY_EVENT`** (User v1, EUI à la version atteinte), résultat et `IDENTITY_COMPLETION_EVENT` tous atomiques |
| E02-E | `Link` / `Unlink` gagnants et demande Identity rejetée | Respectivement événement EUI et complétion ; ou aucune mutation/événement Identity et seule complétion ; aucune mutation partielle |
| E02-F | Injection d'échec **avant la première écriture métier**, après mutation métier, après événement(s) de mutation, après résultat/terminalisation, après publication de complétion et avant commit | Dans chaque cas, rollback total ; aucune version, événement, résultat ni position orpheline |
| E02-G | Commit réussi mais réponse Worker perdue / erreur réseau simulée après résolution physique | Une seule terminalisation et un seul effet après récupération, aucune republication ; slot absent ; comparer aux cas rollback + réponse perdue |
| E02-H | Deux Workers tentent de finaliser la même demande après expiration/reprise de claim | Une seule transaction gagne ; aucun doublon d'effet, résultat ou complétion ; perdant rollback conformément à CON-04 |
| E02-I | Tentative d'écrire un événement de mutation sans `requestId`, ou avec `requestId` incorrect/inexistant | Opération refusée sans commit partiel ; contraintes métier et de persistance effectivement démontrées |
| E02-J | Crash/redémarrage après admission `PENDING` mais avant finalisation | Le traitement est retrouvable et termine une fois ; aucune complétion fantôme ; relation demande/résultat/événements conservée |

### Variantes de transactions et couplage des tests

- Les familles de publication sont distinctes : `POT → COMMAND_COMPLETION`, `INVITATION → COMMAND_COMPLETION` ou `IDENTITY → IDENTITY_COMPLETION` dans le même commit, mais **aucun ordre global entre les cinq séquences**.
- Pour `Registration`, forcer un échec après le premier événement Identity et vérifier qu'aucun des deux ne persiste. Contrôler leurs `subjectVersion` et leur `requestId` commun.
- Vérifier les contraintes à la frontière **SQL réelle**, y compris lorsqu'un writer JDBC/JPA lève une erreur rattrapée par un appelant : l'unité physique doit être condamnée et ne pas committer d'effets partiels. La certification précise du manager commun et de l'empoisonnement des writers est conjointe avec CON-06.
- Pour distinguer crash avant commit et réponse perdue après commit, orchestrer les points de coupure à l'aide de barrières/fault injection déterministes, sans en inférer le résultat uniquement de l'exception remontée au Worker.
- Ne pas supposer qu'un `CommandResult` ou `IdentityRequestResult` READ est immédiatement visible après le commit WRITE : leur matérialisation indépendante relève de PRJ/READ ; EVT-02 prouve l'atomicité **WRITE**.

### Preuves attendues et PASS

Conserver les identifiants de demandes, snapshots SQL avant/après, journaux d'entrelacement/fault injection, transactions et issues observées, comptages/positions par famille, assertions d'unicité et contraintes, ainsi que les résultats de répétitions. **PASS EVT-02** exige E02-A à E02-J sur PostgreSQL réel, un résultat terminal unique par demande durablement terminale, l'absence d'effet partiel à toutes les frontières injectées et la récupération correcte des commits incertains. Aucun test ni résultat n'est revendiqué ici.

### EVT-03 — Protocole et oracles

**Contrat :** exactement sept familles logiques de consommables, dont deux burnables (COMMAND, IDENTITY_REQUEST) et cinq événements non-burnables séquencés. Aucun taskId, famille READ burnable ni préparation de tâches ; les trois Consumptions de POT_EVENT sont autonomes.

| ID | Stimulus | Oracle |
|---|---|---|
| E03-A | Inventorier sources et destinataires | Sept familles, cinq séquences, deux burnables, trois Consumptions indépendantes de POT_EVENT, plus MaterializeInvitation |
| E03-B | Un POT_EVENT(P,V) | Trois slots distincts, un par Consumption ; aucune tâche secondaire |
| E03-C | Rejouer la Discovery d'une même Consumption | Slot idempotent, aucun doublon |
| E03-D | Discovery concurrentes | Un enrôlement durable par couple Consumption/événement |
| E03-E | Échec pendant l'enrôlement | Aucun checkpoint sans tous les slots requis pour la Consumption |
| E03-F | Commit d'enrôlement à issue inconnue | Relecture durable, pas de nouvel ID arbitraire |
| E03-G | Une Consumption réussit, deux échouent | Effets et suppression de son seul slot atomiques, autres slots conservés |
| E03-H | Replay indépendant avec nouvel ID de Consumption | Espace de slots distinct, aucun effet double dans la destination prévue |
| E03-I | Reconstruction READ à blanc | Reset cohérent, mêmes IDs de Consumption utilisables |
| E03-J | Exécutions hors ordre V, V+1 | Versions immuables complètes, CurrentPot et visibilité monotones, pas de dépendance entre slots |

Tests PostgreSQL concurrents, injection de rollback et commit inconnu, contrôle depuis connexion indépendante. PASS seulement si toutes les garanties E03-A à J sont démontrées.

## 6. Consumption et Discovery

### CON-01 — Protocole et oracles

**Statut : protocole proposé, non exécuté.** Contrat V3-4 : la Discovery crée des slots durables à partir des références de sources ; l'Engine seul charge ensuite les données métier. Les quatre familles événementielles sont **non-burnables**, avec chacune sa séquence **globale** dense et un checkpoint de **positions examinées**, propre à `(consumptionId, partitionId)`. Les deux familles burnables (`COMMAND`, `IDENTITY_REQUEST`) sont découvertes par état `PENDING` et absence de slot, **sans checkpoint**. Les partitions sont fixes, le routage par `partitionHash` stable, et une seule propriété effective autorise l'enrôlement.

### Oracles non-burnables

- Pour une Consumption C et sa partition P, le checkpoint `K(C,P)` (initialement zéro) signifie : **toutes les positions globales ≤ K ont été examinées** selon le prédicat de partition de C, et toutes celles éligibles qui devaient être enrôlées ont leur slot ou ont été correctement terminées. Il **ne** signifie pas que les événements de ces positions ont été exécutés.
- Dans une fenêtre globale bornée `(K,H]`, la Discovery filtre le routage vers P, crée les slots de **tous** les événements correspondants et avance le checkpoint à la **dernière position effectivement couverte**, y compris lorsque aucun événement de la fenêtre n'appartient à P. Une fenêtre vide n'autorise pas l'avancement au-delà d'une borne prouvée sûre.
- La borne H doit être un **préfixe durable visible sans trou de publication encore possible** ; l'existence d'une position élevée, un maximum observé ou une séquence réservée non committée ne suffit pas à justifier un saut par-dessus une position qui pourrait apparaître ensuite. La garantie de densité EVT-01 est une précondition ; les tests doivent vérifier la cohérence de la borne utilisée, sans prescrire d'index ni un algorithme SQL particulier.
- La création des slots requis et l'avance de K sont dans **une même transaction de la base des effets**, protégées par le contrôle du checkpoint et de la propriété de partition. Une lecture préparatoire extérieure à cette transaction doit être revalidée avant commit si son snapshot ou sa propriété est devenu obsolète.
- Si le plafond souple de slots présents bloque un lot, **ne pas sauter les événements éligibles non enrôlés** ; ne progresser que sur un préfixe effectivement couvert. Un événement déjà en dessous de K ne doit jamais être réenrôlé par la Discovery ordinaire.

### Oracles burnables

- Pour chaque source `PENDING` routée vers P, créer au plus un slot durable de clé `(consumptionId,consumableId)`. Une source `TERMINAL` n'est jamais nouvellement enrôlée.
- `PENDING` avec slot libre/claimé/expiré signifie **déjà enrôlé** et ne doit pas générer de doublon. `PENDING` sans slot est éligible. Le plafond soft peut retarder l'enrôlement, sans le faire perdre.
- Il n'y a **ni checkpoint burnable ni besoin de parcourir l'histoire des états TERMINAL**. Chaque demande métier (`COMMAND` ou `IDENTITY_REQUEST`) cible sa Consumption WRITE configurée ; il n'existe aucune tâche READ burnable.

### Cas déterministes

| ID | Stimulus / entrelacement | Preuve décisive |
|---|---|---|
| C01-A | Initialiser `K=0`, publier des événements sur plusieurs partitions entremêlées d'une même séquence | Chaque partition découvre exactement ses références éligibles ; son checkpoint peut franchir des positions d'autres partitions sans y créer de slot |
| C01-B | Fenêtre `(K,H]` ne contenant que des événements d'autres partitions | Avance légitime à H malgré zéro slot pour P ; aucun blocage par absence d'événement local |
| C01-C | Limiter la fenêtre globale à N positions ; injecter plusieurs centaines d'événements entremêlés | Progression par fenêtres bornées, aucune position éligible omise aux frontières de lots |
| C01-D | Transaction de publication T1 en suspens sur la position suivante, T2 concurrente, puis rollback ou commit T1 | Aucun checkpoint ne passe une position susceptible d'apparaître ensuite ; reprise découvre l'événement durable réel ; articulation avec EVT-01 |
| C01-E | Deux Discoveries de la même Consumption/partition lisent le même K puis tentent d'enrôler | Une seule avance cohérente, slots uniques, aucune perte ; tentative obsolète rechargée/abandonnée |
| C01-F | Propriété de partition transférée après lecture préparatoire, avant transaction d'enrôlement | Ancien propriétaire ne commite ni slots ni checkpoint ; nouveau propriétaire reprend à K durable |
| C01-G | Plafond souple atteint au milieu d'un lot comportant des événements éligibles | Pas de saut au-delà d'un événement non enrôlé ; progression reprend après libération de capacité |
| C01-H | Slot non-burnable finalisé et supprimé, puis nouveau cycle de Discovery | Aucun réenrôlement si sa position est ≤ checkpoint ; absence du slot seule ne vaut pas preuve de fin |
| C01-I | Pour chacune des deux familles burnables : sources PENDING avec et sans slot, sources TERMINAL, lots répétés | Seules les PENDING sans slot sont enrôlées ; unicité même avec Discoveries concurrentes ; aucun checkpoint |
| C01-J | PENDING avec claim expiré ou retryAfter futur, et plafond atteint | Slot existant respecté ; aucune seconde ligne, aucun contournement de retryAfter par Discovery |
| C01-K | Nouvelle Consumption sur un historique conservé, avec checkpoints absents | Checkpoints à zéro pour cette Consumption ; scan indépendant et enrôlement selon la nouvelle identité sans perturber l'ancienne |
| C01-L | Campagne avec beaucoup d'événements hors partition, slots actifs, sélections répétées | Mesurer références lues, parcours de fenêtres, écritures, SQL, temps de cycle et mémoire ; pas de charge métier complète dans Discovery |

### Méthode, preuves et conditions PASS

JUnit avec **PostgreSQL réel**, plusieurs connexions, barrières contrôlant lecture préparatoire, acquisition de propriété, commit et rollback. Vérifier depuis une connexion indépendante `K`, la liste complète des sources éligibles, les clés de slots, leur unicité et l'absence de slots d'autres partitions. Injecter les publications et les modifications de statut aux frontières pertinentes. Les scénarios de crash et l'atomicité complète de checkpoint/slots sont approfondis dans **CON-02**.

Conserver traces des fenêtres globales examinées, séquences, partitions, positions, checkpoints avant/après, slots et issues transactionnelles, ainsi que mesures de lectures/SQL. **PASS CON-01** requiert C01-A à C01-L, exhaustivité, non-réenrôlement et distinction prouvée entre les deux types de sources. Aucun résultat d'exécution n'est revendiqué.

**Point de vigilance de conception :** le contrat ne prescrit pas encore la construction SQL de la borne sûre H ni la stratégie de réservation de capacité sous concurrence. Leur réalisabilité sans trou, sans famine et sans scans coûteux doit être justifiée dans le plan d'implémentation V3-6 et vérifiée en V3-7 ; toute incompatibilité avec le contrat doit revenir devant V3-CADRE plutôt qu'être cachée par un test permissif.

**Contrat V3-4 :** fenêtre cohérente de positions globales committées `(K,H]`, séquence dense et transactionnelle, filtrage par partition, enrôlement de tous les slots requis et avancement du checkpoint atomiques dans la base des effets sous CAS/ownership. La lecture de la source peut être préalable et inter-bases ; aucune transaction distribuée présumée.

**Scénarios JUnit/PostgreSQL :** publication concurrente avec compteur verrouillé ; commit et rollback ; observation d'une fenêtre homogène malgré transaction retardée ; positions d'autres partitions ; course de deux Discoveries ; crash entre lecture source et commit cible ; rollback sur échec d'insertion slot ; non-régression de K et absence de perte. Aucune preuve exécutée.

### CON-02 — Protocole et oracles

**Statut : protocole proposé, non exécuté.** Le checkpoint non-burnable `(consumptionId,partitionId,position)` désigne le dernier événement **examiné**, non le dernier traité. Sa progression et l'enrôlement de **tous les slots requis dans la fenêtre** sont dans une seule transaction physique de la base des effets. L'exécution d'un slot le supprime uniquement dans la même transaction que les effets et leurs validations. Aucun checkpoint de complétion ni état DONE n'est ajouté.

### Invariants et oracle

Pour chaque événement E non-burnable de position p, destiné à Consumption C et partition P, après franchissement durable de p par `K(C,P)` :

- Tant que les effets de E ne sont pas durablement appliqués, il existe **exactement un slot** `(C,E)` (libre, réclamé, expiré ou en retry).
- Après suppression définitive de ce slot, les effets de E sont durablement appliqués et ne peuvent plus être annulés isolément.
- La proposition `K ≥ p ET slot absent ⇒ effets déjà committés` n'est correcte **que sous les hypothèses de conservation et d'absence d'autres chemins de suppression**. Avant franchissement du checkpoint, l'absence de slot ne prouve rien.
- Le scan d'une fenêtre, la protection de K/propriété de partition, l'insertion des slots nécessaires et l'avance de K se résolvent ensemble par commit ou rollback ; un lot interrompu n'est jamais à moitié découvert.
- Les redémarrages ne recréent pas des slots d'événements déjà couverts, ni ne sautent les événements jamais enrôlés.

### Cas déterministes

| ID | Injection / entrelacement | Assertions depuis une autre connexion PostgreSQL |
|---|---|---|
| C02-A | Échec avant l'insertion du premier slot d'une fenêtre | K inchangé ; aucun slot de cette fenêtre ; reprise crée tous les slots attendus |
| C02-B | Échec après insertion du premier slot mais avant les suivants | Rollback intégral, K inchangé et aucun slot partiel |
| C02-C | Échec après insertion de tous les slots mais avant l'avance de K | Aucun slot durable et K inchangé après rollback |
| C02-D | Échec après avance de K mais **avant commit** | Ni K avancé ni slots créés après rollback |
| C02-E | Commit complet de la fenêtre puis réponse de commit perdue | K avancé et ensemble exact des slots présent ; retry de Discovery ne crée ni doublon ni perte |
| C02-F | Fenêtre sans événement de cette partition, mais avec positions globales couvertes | K peut avancer atomiquement sans nouveaux slots ; les autres partitions demeurent inchangées |
| C02-G | Deux Discoveries concurrentes ont lu K initial, l'une commit, l'autre tente de finaliser un snapshot périmé | Une seule avance cohérente, l'autre recharge ou abandonne ; slots exacts, K monotone |
| C02-H | Transfert de propriété de partition entre préparation de la fenêtre et transaction finale | Ancien propriétaire ne commite ni K ni slots ; nouveau propriétaire reprend sur état durable |
| C02-I | Un slot traité avec effet durable et suppression, mais panne **avant commit** | Rollback de l'effet et de la suppression : slot reste présent |
| C02-J | Même effet/suppression avec commit réussi et accusé de réception perdu | Slot absent, effets durables, aucune seconde exécution à effet |
| C02-K | Après traitement de certains slots d'une fenêtre, redémarrer Discovery puis Engine | K reste à la position examinée ; seuls les slots inachevés restent ; aucun slot terminé ne réapparaît |
| C02-L | Simuler un reset partiel (slots seuls, checkpoints seuls, projections seules) ou un changement de Consumption avec réutilisation fautive de son ID | Opération administrative non autorisée/refusée ou détection explicite de violation ; seul le reset READ coordonné (REC-01) ou le nouvel ID de Consumption est admis |

### Protocole d'injection et de constat

- PostgreSQL réel, connexions et transactions indépendantes. Des barrières arrêtent le flux aux frontières **pré-commit** ; injecter exceptions, rollback explicite, déconnexion, interruption du Worker et perte simulée de la réponse de commit. Ne pas assimiler une déconnexion à la preuve d'un rollback : inspecter ensuite l'état durable.
- Capturer `K`, liste des sources éligibles de la fenêtre, clés `(consumptionId,consumableId)` attendues, slots effectivement persistés, effets métier/projection, statuts des tâches si pertinents, et issues transactionnelles.
- La lecture préparatoire de Discovery ne crée aucun état de complétion. Une éventuelle lecture cross-base des sources ne rend **pas** la transaction finale distribuée : checkpoint et slots doivent appartenir à l'autorité transactionnelle des effets de cette Consumption.
- Vérifier les contraintes d'unicité et les seuls chemins autorisés de suppression ; un `DELETE` administratif direct hors transaction de reset briserait l'oracle, et ne doit pas être présenté comme un comportement sûr.
- Couvrir une publication concurrente et la borne de préfixe sûr avec CON-01 ; l'ordre des verrous et la propriété de partition sont approfondis avec CON-03/06.

### Critère PASS

**PASS CON-02** requiert C02-A à C02-L, absence de demi-enrôlement et de demi-finalisation, monotonie de K, exactitude des slots et récupération sans travail perdu ni double effet. Exiger une preuve que la discipline de suppression/reset est respectée par **tous** les writers, pas seulement par le chemin nominal. Aucun test V3 n'a encore été implémenté ou exécuté.

### CON-03 — Protocole et oracles

**Statut : protocole proposé, non exécuté.** Le lease de partition (propriétaire, `ownerToken`, échéance) autorise uniquement **Discovery et acquisition de nouveaux slots**. Le claim de slot (`claimToken`, `claimedUntil`) autorise renouvellement et finalisation de **ce slot**, même si le Worker a entre-temps perdu la propriété de partition. Le `partitionOwnerToken` n'est pas recopié dans le slot et aucun verrou de partition n'est exigé dans la transaction de finalisation. L'expiration du claim ne permet sa reprise qu'après vérification transactionnelle sous verrou du slot.

### Oracles de correction

- À un instant donné, au plus un propriétaire de partition possède le droit effectif d'enrôler ou d'acquérir. Un ancien `ownerToken` n'autorise aucune nouvelle Discovery ni acquisition après takeover.
- La perte de partition **n'invalide pas** un claim déjà acquis : l'ancien Worker peut le renouveler et finaliser sous son `claimToken` valide. La possession actuelle de la partition n'est **pas** une précondition de finalisation.
- Un nouveau propriétaire peut acquérir les slots libres ; il ne peut prendre un claim encore valide appartenant à l'ancien Worker. Un claim expiré peut être remplacé par un nouveau token, avec condition et sérialisation SQL.
- La validité du claim est contrôlée à **l'acquisition du verrou exclusif final sur le slot**, à une date DB fraîche après toute attente de verrou. Une fois la protection obtenue et le verrou détenu jusqu'au commit/rollback physique, l'expiration murale ultérieure ne suffit pas à invalider ce commit.
- Renouvellement, takeover et finalisation sur **le même slot** sont sérialisés. Un `claimToken` remplacé ne peut ni renouveler, ni libérer, ni finaliser le nouveau claim.
- L'expiration de partition n'entraîne **aucune reprise anticipée des claims individuels**. Les bornes de temps de partition et de claim sont indépendantes.

### Cas PostgreSQL déterministes

| ID | Entrelaçement contrôlé | Oracle |
|---|---|---|
| C03-A | Worker A propriétaire acquiert slot S ; transfert de partition à B pendant que le claim de A est encore valide | B peut découvrir/acquérir d'autres slots mais **pas S** ; A peut terminer S ; aucune consultation de propriété de partition à la finalisation |
| C03-B | Après transfert, A tente une nouvelle Discovery et une nouvelle acquisition avec son ancien ownerToken | Les deux sont refusées sans création de slot ni changement de claim |
| C03-C | Après transfert, A renouvelle son claim S encore valide, alors que B renouvelle son lease de partition | Renouvellement de S possible sans droit de partition ; les deux opérations indépendantes ne se bloquent pas par un verrou de partition final |
| C03-D | Lease de partition A expiré, claim S non expiré ; B devient propriétaire | B ne reprend pas S avant expiration propre de `claimedUntil` |
| C03-E | Claim S expiré ; B tente takeover pendant que A tente le renouvellement tardif | Un seul gagnant selon verrou/conditions ; claim expiré **non ressuscité** ; ancien token incapable de finaliser |
| C03-F | A acquiert verrou final de S avec claim valide ; `claimedUntil` passe pendant le commit encore en cours ; B tente takeover | Le commit A peut réussir sous verrou ; B attend puis constate slot absent ou issue durable et ne produit pas de double effet |
| C03-G | A attend le verrou de S et son claim expire **pendant l'attente** ; B est en position de reprendre | A doit constater l'expiration à l'acquisition effective du verrou et ne peut finaliser grâce à une lecture antérieure de l'horloge DB |
| C03-H | B remplace le claim expiré de S avant que A prenne le verrou final ; A revient avec ancien token | A ne finalise ni ne libère S ; seul token B peut gouverner le traitement |
| C03-I | B perd lui-même sa partition après takeover de S mais garde un claim S valide | Même invariant récursivement : claim B demeure indépendant et peut terminer |
| C03-J | Expiration, takeover et crash/commit incertain pendant la finalisation | Observer slot et effets depuis connexion indépendante ; un seul traitement durable, aucune réouverture ou reprise anticipée |
| C03-K | Plusieurs partitions fixes et deux Workers partagent la même déclaration de Consumption ; alternance des propriétaires | Acquisitions exclusivement autorisées par propriétaire effectif de chaque partition ; claims anciens valides et routage stable |
| C03-L | Métriques et traces d'une campagne répétée avec changements de propriétaire et claims de durées différentes | Aucun double commit ; taux de takeover, attente de verrou, reprises et expirations correctement observables |

### Méthode et pièges à exclure

JUnit multi-connexions contre **PostgreSQL réel**, barrières contrôlant prise du verrou, tentative de renouvellement, takeover, commit et rollback. Utiliser une horloge DB maîtrisable / délais d'expiration explicites et relecture DB *après verrou* ; ne pas fabriquer la preuve avec des `sleep` probabilistes. Contrôler à la fois le contenu durable des slots, les tokens (dans les assertions internes uniquement), les ownerTokens et les effets finaux. Ne jamais exposer ces tokens en métriques cardinales ou logs sensibles.

Une stratégie fondée sur `now()` PostgreSQL pris au **début d'une longue transaction** ne suffit pas à C03-G ; la validation doit employer une horloge dont la fraîcheur après attente est prouvée. Le contrat ne prescrit ni DDL ni SQL exact : l'implémentation devra démontrer cette propriété.

C03-F ne permet **pas** à un claim déjà expiré avant protection finale d'être accepté : la distinction porte sur l'instant d'obtention du verrou final. Le transfert de partition ne constitue pas un motif de rollback d'une transaction déjà protégée.

**PASS CON-03** : C03-A à C03-L réussis, absence de reprise anticipée et de double effet, validation temporelle au verrou final, absence de dépendance de finalisation à l'ownerToken de partition. Recoupements obligatoires avec CON-04 (commit unique) et CON-06 (transaction physique). Aucun test V3 n'est exécuté à ce stade.

### CON-04 — Protocole et oracles

**Statut : protocole proposé, non exécuté.** Un slot de clé `(consumptionId,consumableId)` ne peut finaliser qu'une fois. La transaction physique finale protège le `claimToken` encore valide au **moment d'acquisition effective du verrou final**, les invariants de domaine, les effets de la Consumption et la suppression du slot. La suppression n'est jamais une étape indépendante. Une réponse de commit perdue n'autorise pas une réexécution aveugle.

### Oracles

- **Au plus un effet durable** pour une clé de travail, même si plusieurs Workers ont préparé le même calcul ou si une tentative a reçu un résultat de commit inconnu.
- La finalisation doit constater le **claim courant** sous protection exclusive ; un ancien token, un claim expiré avant protection ou un slot absent interdit le commit des effets de ce traitement.
- La suppression du slot et les effets se produisent **ensemble** ou pas du tout ; si la source est burnable, sa transition `PENDING → TERMINAL` et son résultat éventuel partagent la transaction finale ; si elle est non-burnable, `checkpoint ≥ position` et slot absent ne deviennent preuve de complétion qu'avec les invariants CON-02.
- Une fois la protection finale acquise correctement, un takeover concurrent ne peut pas intervenir avant la résolution physique du commit/rollback. Le dépassement de `claimedUntil` **après** la prise du verrou n'annule pas rétroactivement le droit acquis.
- L'Engine ne recrée jamais de slot pour résoudre un commit inconnu. Il vérifie l'état durable : slot absent implique une finalisation constatée sous les invariants ; slot présent implique une éventuelle nouvelle acquisition complète selon son claim et `retryAfter`. Un état inconnu local n'est pas un nouveau statut durable.

### Cas PostgreSQL déterministes

| ID | Entrelacement ou faute injectée | Assertion décisive |
|---|---|---|
| C04-A | Deux Workers préparent le même slot et tentent la validation avec le même token | Une seule transaction applique l'effet ; le second ne peut pas appliquer une seconde fois après disparition du slot |
| C04-B | Claim de A expiré ; B reprend avec nouveau token avant finalisation de A | A ne peut ni supprimer le slot ni écrire ses effets ; seul B peut finaliser |
| C04-C | A verrouille le slot valide puis B tente un takeover après franchissement de l'échéance | B est sérialisé derrière A ; après commit, B voit slot absent et n'applique aucun effet |
| C04-D | A obtient le verrou mais déclenche une exception après écriture d'une partie des effets | Rollback des effets, source terminale éventuelle et suppression ; slot encore présent pour reprise |
| C04-E | A commite complètement, mais l'accusé de commit est perdu ; Worker redémarre | Lecture durable détecte slot absent et effets complets ; aucun effet ni événement supplémentaire |
| C04-F | A rollback, mais sa connexion est coupée et le résultat est inconnu côté application | Lecture durable retrouve slot présent et effets absents ; reprise conditionnelle, nouvelle acquisition et nouvelle tentative |
| C04-G | Un claim ancien tente de renouveler ou libérer un slot désormais repris par B | Aucune modification de l'état de B ; le token courant n'est pas écrasé |
| C04-H | Propriété de partition transférée alors que A détient encore un claim valide | A reste autorisé à finaliser ; la propriété courante de partition n'est pas consultée comme fence final |
| C04-I | Deux exécutions de la même demande burnable se disputent le slot avec fault injection sur `TERMINAL` et résultat | Une seule terminalisation et un seul résultat ; jamais `TERMINAL` sans résultat correspondant |
| C04-J | Deux Consumptions READ concurrentes visent des versions différentes ou la même version | Chaque slot se termine une fois ; idempotence des projections et ordre/version assurés par PRJ-01/02/03, sans imposer l'ordre des slots |
| C04-K | Concurrence répétée, takeover, rollbacks et interruptions de Workers | Nombre d'effets et publications conforme aux seules finalisations committées ; aucune duplication ni travail durablement perdu |
| C04-L | Revue des voies SQL d'écriture/suppression du slot et cas d'administration | Aucune voie ne supprime ou ne finalise hors transaction protégée ; reset coordonné READ seul cas d'effacement administratif permis (REC-01) |

### Organisation des preuves

JUnit/PostgreSQL réel avec **connexions indépendantes**, barrières de synchronisation sur acquisition du claim, tentative de verrou final, vérification temporelle post-verrou, mutation partielle et commit. Les délais d'expiration sont contrôlés ; les `sleep` ne constituent pas une preuve d'ordre. Enregistrer snapshots SQL avant/après, tokens uniquement dans les assertions privées, nombres d'effets et positions événementielles, issue réelle de commit et observations côté Worker.

Distinguer explicitement : (a) réponse d'échec avant commit, (b) rollback physiquement certain, (c) commit réussi/réponse perdue, (d) issue momentanément inconnue. **Une erreur réseau n'est pas une preuve d'échec du commit.** Si une observation immédiate ne tranche pas, la récupération attend la résolution de la transaction/expiration du claim et relit l'état durable, sans créer `UNKNOWN` persistant.

### Critère PASS

**PASS CON-04** exige C04-A à C04-L, une seule finalisation durable par slot, effets et suppression indivisibles, absence de double effet après takeover ou commit incertain, et sécurité des sources burnables/non-burnables. Preuves conjointes CON-02, CON-03, EVT-02 et CON-06 obligatoires. Aucun test V3 ni résultat de certification n'est revendiqué.

### CON-05 — Protocole et oracles

**Contrat normatif :** une acquisition = une tentative complète, rechargement de la source, des versions et préconditions. Un échec technique explicitement constaté est enregistré une seule fois sous le claim valide, avec rollback des effets métier. Tant que attemptCount<N, cession conditionnelle et retryAfter ; au Nᵉ échec constaté, QUARANTINED. Expiration de claim/crash sans échec durable ne comptent pas. Aucun retry interne au même claim, pas de faux rejet terminal.

### Cas déterministes

| ID | Stimulus / faute | Assertion décisive |
|---|---|---|
| C05-A | Exception de chargement ou préparation avant transaction finale | Aucune mutation ; tentative abandonnée ; cession conditionnelle et retryAfter ; pas de seconde exécution sous le claim |
| C05-B | Exception technique pendant validation/écriture de la transaction finale | Rollback complet, y compris effets et slot ; cession du claim dans transaction séparée et courte ; retryAfter durable |
| C05-C | Conflit CAS sur la version de travail d'un Pot après préparation | Aucune terminalisation/rejet automatique ; après retryAfter et réacquisition, nouvelle version de travail lue et intention réévaluée |
| C05-D | Demande effectivement obsolète selon faits à la version rechargée | Résultat **métier** rejeté et complétion atomique ; aucun retry technique infini |
| C05-E | Source/payload/version modifiés entre deux acquisitions selon les contrats autorisés | Nouvelle tentative recharge les faits autoritatifs ; payload d'intention de la demande reste inchangé |
| C05-F | Tentative d'acquisition avant retryAfter alors que le slot est libre | Acquisition refusée ; acquisition possible à partir de l'échéance si les autres conditions sont satisfaites |
| C05-G | A échoue ; son claim expire et B le reprend avant que A tente de le libérer | La libération tardive de A n'altère ni le claim B ni son retryAfter ; ancien token non réutilisable |
| C05-H | Cession conditionnelle commit, mais réponse perdue ; puis variante rollback de la cession | Relire le slot et ses échéances ; pas de double cession, ni de nouvelle ligne, ni de reprise immédiate illicite |
| C05-I | Plusieurs échecs techniques consécutifs avant succès | Chaque tentative a un token différent et une exécution complète ; temporisation respectée ; aucun état FAILED/UNKNOWN ajouté |
| C05-J | Erreur technique persistante (base indisponible, violation structurelle, bug reproductible) | Slot reste récupérable, aucune terminalisation mensongère ; diagnostic explicite, cadence bornée par retryAfter |
| C05-K | Crash du Worker après rollback et avant cession du claim | Aucun effet partiel ; claim expirera normalement et pourra être repris, sans reconstruction de slot |
| C05-L | Worker perd la partition entre échec et cession, mais garde son claim individuel | Cession conditionnelle reste possible avec claim courant ; l'ancien Worker ne peut pas acquérir de nouveau slot après perte de propriété |
| C05-M | Vérification instrumentée sur erreurs, reprises et refus métier | Compteurs/timers distinguent tentative, retry, issue technique, rejet métier et succès ; pas de double comptage E2E |

### Protocole d'essai et preuves attendues

JUnit sur PostgreSQL réel avec connexions distinctes et barrières/fault injection aux points de chargement, préparation, CAS, écriture, rollback, cession et réacquisition. Observer depuis une autre connexion l'état durable du slot, `claimToken`, `claimedUntil`, `retryAfter`, la source PENDING/TERMINAL, les versions, résultats et événements. Les tokens sont réservés aux assertions internes, jamais aux métriques ou logs exposés.

Tester séparément les retards de disponibilité introduits par `retryAfter` et l'attente résultant d'un claim encore détenu. Un chrono applicatif n'est pas une horloge DB : toute comparaison d'échéance doit être validée avec la sémantique SQL réellement choisie. Le protocole n'impose pas un algorithme de backoff sophistiqué ni un nouveau compteur durable.

**PASS CON-05** : C05-A à C05-M satisfaits ; rollback avant toute reprise, une seule exécution par acquisition, libération conditionnelle non destructive, respect de retryAfter, rechargement complet et distinction démontrée entre conflit technique et rejet métier. Recoupements CON-03/04, DOM-02 et observabilité V3-5B. Aucun test V3 n'est encore exécuté.

**Décision révisée :** budget N de tentatives techniques, `retryAfter` tant que budget disponible, puis `QUARANTINED` ; exclusion du softCap et de l'acquisition automatique. Le slot reste durablement traçable et non terminal tant que le protocole de purge/résolution n'est pas prouvé.

**Tests à préparer :** N-1 échecs puis succès ; N échecs vers quarantaine ; softCap occupé par quarantaines, autre Pot indépendant progresse ; même Pot avec dépendance non satisfaite ne produit pas d'effet incorrect ; burnable PENDING ne se réenrôle pas après quarantaine ; non-burnable checkpoint ne fait pas passer slot absent pour complété ; reprise administrative puis succès ; crash/expiration ne doivent pas consommer arbitrairement des tentatives sans contrat explicite. Aucun test réalisé.

À prouver sur PostgreSQL : slot QUARANTINED ignoré par acquisition et Discovery malgré redémarrage/déploiement/écoulement du temps ; reprise administrative sur identifiant exact et état attendu ; course de deux administrateurs (une seule transition effective) ; reprise d'un slot non quarantainé refusée ou idempotence définie ; aucune mutation de source ou projection lors de la réactivation ; nouvelle acquisition réexécute entièrement le traitement ; reprise redevient soumise aux limites ordinaires de capacité ; absence de ré-enrôlement du même travail. Action d'administration et gestion du budget au nouveau cycle à détailler avant implémentation. Aucun test exécuté.

Scénarios PostgreSQL exigés : (1) expiration de claim sans erreur enregistrée, puis réacquisition : `attemptCount` inchangé ; (2) un échec explicitement persisté incrémente une seule fois ; (3) `N-1` échecs puis succès ; (4) Nᵉ échec -> `QUARANTINED` ; (5) tentative tardive avec ancien `claimToken` après réacquisition : aucune incrémentation ni transition ; (6) réactivation administrative atomique de `QUARANTINED` -> éligible avec `attemptCount=0` et même identité ; (7) crash avant persistance de l'échec : aucun budget consommé. N reste configurable, sans chiffre arbitraire imposé. Aucun test V3 exécuté.

Tests à construire : mettre Pot P version V en quarantaine, prouver la progression de P version V+1 dans la même Consumption et celle des autres Consumptions (la lecture courante avec `minVersion=V` réussit en `200` avec `V+1` ; la lecture historique à `V` retourne `404` si `PotVersion(P,V)` est absente en quarantaine, ou `503` après autorisation si `PotVersion(P,V)` est présente mais `PotBalance(P,V)` est en quarantaine) ; visibilité V+1 complète matérialisée depuis WRITE@V+1 malgré V en quarantaine, puis reprise tardive de V sans régression ; CurrentBinding hors ordre ; autre Pot dans la même partition non bloqué. Tester absence de toute attente sur statut du prédécesseur. Aucun test exécuté.

**Scénarios de certification complémentaires :** (a) slot QUARANTINED conserve son identité et son état malgré redémarrages, reprise Discovery et écoulement du temps ; (b) aucune commande de purge directe d'un slot incomplet ; (c) pour non-burnable, checkpoint avancé + slot quarantainé ne vaut jamais preuve de traitement ; (d) pour burnable, PENDING + slot quarantainé ne provoque aucun ré-enrôlement ; (e) réactivation administrative ne supprime ni slot ni source ; (f) finalisation réussie : effets métier et suppression du slot dans une seule transaction ; (g) finalisation échouée : slot encore présent, pas de complétion ; (h) autres slots, y compris même agrégat, progressent. Archivage futur hors périmètre V3. Aucun test exécuté.

**Oracles complémentaires AUD-V3-007/Q :** N-1 échecs puis succès ; N échecs vers quarantaine ; exclusion du softCap ; course ancien/nouveau claim sans double comptage ; redémarrage n'entraînant aucune réactivation ; intervention administrative ciblée et atomique avec attemptCount=0 ; seconde intervention concurrente sans double effet ; version V+1 du même Pot et autres Consumptions progressent malgré V en quarantaine ; slot incomplet jamais purgé ; seule réussite durable supprime atomiquement le slot.

### CON-06 — Protocole et oracles

**Statut : protocole proposé, non exécuté.** Toute finalisation d'une Consumption doit coordonner sa protection du claim, les validations, les effets persistants, la mise à jour éventuelle de la source burnable et la suppression du slot **dans la même transaction physique, sur une seule autorité de persistance**. Les opérations préparatoires peuvent lire une autre base, mais les effets atomiques et les métadonnées du traitement résident ensemble. Un Worker mixte héberge des Consumptions WRITE et READ sans jamais partager une transaction physique entre ces royaumes.

### Matrice des autorités à prouver

| Consumption / nature | Autorité finale attendue | Écritures indivisibles |
|---|---|---|
| Exécution `COMMAND` | WRITE | Domaine Pot, version/CAS, événements et séquences, résultat terminal, statut source, slot |
| Exécution `IDENTITY_REQUEST` | WRITE | Domaine Identity, événements et séquences, résultat terminal, statut source, slot |
| Matérialisation `POT_EVENT → PotVersion/CurrentPot/SubobjectVersion` | READ | Projections, validation de version, slot |
| Matérialisation directe `POT_EVENT → PotBalance` | READ | PotBalance(P,V) si V active, slot |
| Matérialisation directe `POT_EVENT → PotVisibilityState` | READ | PotVisibilityState, entrées visibles/tombstones, slot |
| Matérialisation événements de complétion et Identity vers READ | READ | CommandResult / IdentityRequestResult / CurrentBinding selon Consumption, slot |

La Discovery, son checkpoint et ses slots s'enregistrent dans l'autorité **des effets** de la Consumption ; le lease de partition correspondant appartient à cette même autorité. Cela ne signifie pas que les événements source et les projections se trouvent dans une seule base : la lecture de la source peut précéder la transaction READ finale, suivie d'une vérification de claim et de version appropriée.

### Oracles structurants

1. **Transaction unique :** les écritures JPA et JDBC affectées à la même Consumption utilisent le même transaction manager PRIMARY et la même transaction **physique** (connexion/identité transactionnelle PostgreSQL effectivement partagée), pas seulement des annotations `@Transactional` portant le même nom.
2. **Rollback empoisonné :** toute erreur durable d'un writer, y compris interceptée localement, empêche le commit partiel de l'unité d'effets. Une erreur SQL PostgreSQL qui invalide une transaction ne doit pas être masquée ; les exceptions applicatives interceptées doivent également marquer explicitement la transaction rollback-only si l'invariant le requiert.
3. **Aucun commit intermédiaire :** interdiction des effets `REQUIRES_NEW` ou autres transactions indépendantes laissant persister métier/projections, séquences, tasks, slots ou résultats séparément.
4. **WRITE et READ :** une Consumption n'écrit ses effets transactionnels que dans son autorité. Composer les deux types dans un Worker ne crée **ni XA/2PC ni commit multi-base**.
5. **Fencing et séquencement :** protection finale du claim et publication dense sont dans cette même transaction ; le verrou final est conservé jusqu'au commit/rollback physique. Les chemins JDBC et JPA ne contournent pas le fencing ni le verrouillage ordonné.
6. **Fail-fast architectural :** une composition possédant des managers différents pour writers qui prétendent partager une unité atomique est refusée à la configuration ou détectée par un test de contrat avant certification.

### Cas de preuve déterministes

| ID | Stimulus / configuration | Assertion décisive |
|---|---|---|
| C06-A | Mutation Pot, émission d'événements, terminalisation Command et suppression slot avec writers JPA et JDBC | Une seule transaction/connexion physique ; tous les effets visibles ensemble après commit |
| C06-B | Registration avec deux Identity events + complétion et slot | Même unité WRITE malgré plusieurs repositories/writers, aucune demi-registration |
| C06-C | Trois Consumptions directes POT_EVENT en READ, succès et rollback séparés | Chacune finalise atomiquement uniquement ses effets et son propre slot |
| C06-D | Matérialisation directe de Balance ou Visibility et suppression du slot | Même transaction READ ; rollback ne conserve ni projection ni suppression de slot |
| C06-E | Writer JDBC échoue, exception interceptée par un niveau appelant, tentative de commit | Le commit est refusé/rollback intégral ; ni slot supprimé ni effet durable |
| C06-F | Writer JPA échoue après un writer JDBC réussi, puis exception interceptée | Rollback de toutes les écritures y compris JDBC ; pas de transaction partielle |
| C06-G | Writer JPA réussit, writer JDBC échoue et l'appelant poursuit | Rollback total ; protection contre toute récupération trompeuse de l'exception |
| C06-H | Composition erronée JPA manager A / JDBC manager B ou datasource secondaire indépendante pour la même Consumption | Démarrage/composition refusé, ou test d'architecture/contrat explicitement FAIL avant qualification |
| C06-I | Injection d'un `REQUIRES_NEW` dans un writer d'effets ou une suppression de slot | Violation détectée par garde/test ; aucun PASS si un effet peut survivre au rollback externe |
| C06-J | Worker mixte exécute successivement des Consumptions WRITE et READ | Deux transactions indépendantes, chacune cohérente dans son royaume ; aucune transaction distribuée |
| C06-K | Discovery READ lit `POT_EVENT` en WRITE, puis enrôle slots et checkpoint en READ | Source lue sans mutation WRITE ; seuls checkpoint et slots READ committés atomiquement ; reprise sûre après échec |
| C06-L | Commit incertain, claim expirant durant verrou final, transfert de partition | Fencing du slot durable jusqu'à commit ; récupération par lecture durable ; aucune protection de partition finale requise |
| C06-M | Vérifier tous les points d'entrée et compositions des writers, y compris tests et mono-runtime | Inventaire des transactions physiques et garde-fous ; aucun chemin de finalisation non couvert |

### Couverture fusionnée et correspondance avec l'ancienne fiche CON-06

La présente fiche est **l'unique protocole canonique CON-06**. Elle conserve les assertions de l'ancienne fiche « autorité transactionnelle physique et compositions mixtes » sans exiger deux campagnes distinctes :

| Ancien cas | Cas canonique et preuve conservée |
|---|---|
| ancien C06-A (Command WRITE) | C06-A |
| ancien C06-B (Registration WRITE) | C06-B |
| ancien C06-C (CurrentPot READ) | **C06-N** ci-dessous |
| ancien C06-D (préparation et tâches distinctes) | C06-C + C06-D |
| ancien C06-E (projections Result) | **C06-O** ci-dessous |
| ancien C06-F (JPA/JDBC dans les deux ordres) | C06-E + C06-F + C06-G |
| ancien C06-G (exception interceptée) | C06-E + C06-G |
| ancien C06-H (manager invalide / REQUIRES_NEW) | C06-H + C06-I |
| ancien C06-I (Worker mixte) | C06-J |
| ancien C06-J (commit inconnu) | C06-L et CON-04 |
| ancien C06-K (Consumers indépendants) | **C06-P** ci-dessous |
| ancien C06-L (inventaire des writers) | C06-M |

| Cas complémentaire conservé | Assertion décisive |
|---|---|
| **C06-N** | Matérialisation `POT_EVENT → CurrentPot/SubobjectVersion` : version courante, sous-objets immuables et suppression du slot forment **une transaction READ** ; la lecture préparatoire de l'événement WRITE ne produit aucune écriture WRITE. |
| **C06-O** | Projection des événements de complétion vers `CommandResult` / `IdentityRequestResult` : résultat et suppression du slot READ dans une transaction physique unique, même en cas de panne/rollback. |
| **C06-P** | Deux Consumptions indépendantes sur la même publication écrivent deux projections READ distinctes : commits autonomes, pas de barrière ni de transaction inter-Consumptions ; convergence même lorsque leurs finalisations sont décalées. |

**PASS CON-06 consolidé** : C06-A à C06-P couverts, avec preuves des frontières WRITE/READ, poisoning de chaque writer, absence de transaction distribuée, composition monolithique/distribuée et absence de voie secondaire. La fusion est éditoriale ; aucun test n'est exécuté.

### Dispositif et artefacts de preuve

Combiner tests d'architecture (composition, managers, annotations interdites, chemins d'accès), tests d'intégration Spring sur les compositions réelles et **PostgreSQL réel** avec injections de fautes. Relever l'identité effective de transaction et de connexion au moment des writers concernés, vérifier les états durables depuis une connexion extérieure après commit/rollback, et tester des erreurs **interceptées**, pas seulement propagées. Couvrir les configurations runtimes séparés **et** monolithiques.

Ne pas supposer qu'un simple partage de `DataSource` ou un nom de transaction manager garantit une transaction physique commune : ce sont des hypothèses à prouver. Les verrous, contraintes et timings exacts seront démontrés dans CON-03/04 et EVT-01. Le protocole ne prescrit pas de framework transactionnel supplémentaire ; une composition non conforme doit être corrigée ou refusée.

**PASS CON-06** : C06-A à C06-P vérifiés, zéro transaction à effets séparés, empoisonnement de tout writer après erreur, routes WRITE/READ indépendantes et absence de 2PC. Les preuves communes EVT-01/02, EVT-03, PRJ-01/02/03 et CON-03/04 sont requises. Aucun code V3 ni test n'a été exécuté.

## 7. Projections READ

### PRJ-01 — Protocole et oracles

**Statut : protocole proposé, non exécuté.** Référence V3-3 : `MaterializeCurrentPot` consomme directement `POT_EVENT(P,V)`, reconstruit `Pot@V` depuis WRITE **sans dépendre de la projection V−1**, conserve les `SubobjectVersion` immuables et avance `CurrentPot` de manière monotone. Une transaction READ unique écrit les sous-objets nouveaux, le remplacement conditionnel du pointeur courant et la suppression du slot. Les branches Balance et Visibility sont indépendantes.

### Oracles et limites contractuelles

- Pour chaque `POT_EVENT(P,V)`, reconstruire directement les valeurs et références de `Pot@V`, avec les versions locales attendues. L'événement contient `changedComponents`, pas une copie de tout le Pot ; il ne faut donc pas supposer que son payload suffise seul.
- `SubobjectVersion(componentId,localVersion)` est immuable et idempotente : elle n'est ni modifiée, ni effacée lors d'une livraison tardive, d'une répétition ou de l'avancement du pointeur. Les versions nécessaires à l'historique restent stockées.
- `CurrentPot(P)` ne recule jamais : mise à jour uniquement si `incomingVersion > storedVersion`, sous protection transactionnelle résistant à deux writers concurrents. Les références que porte le pointeur sont toutes celles de **la même version globale V**, sans mélange de V et V+1.
- Si V+1 est matérialisée avant V, la livraison tardive de V doit **préserver ses sous-versions historiques**, même si la mise à jour CurrentPot(V) est ignorée.
- Le commit de l'écriture READ et de la suppression du slot est indivisible ; rollback et réponse de commit perdue suivent CON-04/06.
- **La disponibilité READ complète est séparée de PRJ-01** : `CurrentPot` peut pointer `V` (`PotVersion(P,V)` et ses `SubobjectVersion` committées atomiquement) tandis que `PotBalance(P,V)` n'est pas encore prête. Le lecteur ne doit pas présenter ce Pot comme complet ; oracle end-to-end de présence dans READ-01.
- La conservation des `PotVersion(P,V)` et `SubobjectVersion` permet à la fois la lecture courante et la consultation historique autorisée `Pot@V` (pour `V < D`, cf. SPEC-04 §10 et SPEC-06 §4).

### Scénarios PostgreSQL déterministes

| ID | Stimulus / ordre | Oracle de réussite |
|---|---|---|
| P01-A | Créer Pot V1 avec sous-objets version 1 puis matérialiser | CurrentPot(V1) avec références exactes ; chaque SubobjectVersion nécessaire présente et identique à Pot@V1 |
| P01-B | Modifier un seul sous-objet : V2 globale, révision locale uniquement du composant concerné | CurrentPot(V2) lie les révisions locales correctes ; objets inchangés conservent leurs références/versions ; aucune révision locale artificielle |
| P01-C | Mutation multi-cibles puis suppression logique d'Expense et suppression Pot | Un seul pointeur global par Pot ; états/suppressions et références exacts à chaque V ; versions immuables antérieures conservées |
| P01-D | Matérialiser `POT_EVENT(V3)` avant `V1` et `V2` | CurrentPot ne descend pas sous V3 ; les arrivées tardives alimentent toutes les versions immuables attendues, sans écraser les données anciennes |
| P01-E | Deux Workers matérialisent V et V+1 concurremment avec barrières au moment du remplacement | CurrentPot finit à max(V,V+1), avec références homogènes ; les deux ensembles de versions immuables conservés |
| P01-F | Répéter une livraison déjà matérialisée ou une reprise après réponse de commit perdue | Aucun doublon ni mutation de version immuable ; slot/effets satisfaits une fois ; pointe courante inchangée ou avancée seulement |
| P01-G | Injection d'échec après insertion de sous-objets mais avant écriture CurrentPot, puis après son écriture avant commit | Rollback intégral et slot intact ; aucune projection partielle durable |
| P01-H | Injecter par fault injection en base READ l'absence d'une SubobjectVersion référencée par PotVersion(P,V) | Lecture complète refusée/temporairement indisponible (`503` après autorisation) jusqu'à présence de toutes les références nécessaires (READ-01) |
| P01-I | Faire avancer CurrentPot V→V+1 pendant une lecture qui a capturé V | Réponse cohérente à la version V capturée ou indisponibilité explicite, jamais mélange de références de versions |
| P01-J | Événement ancien réémis ou traitement concurrent pendant que CurrentPot pointe V+2 | Conservation immuable des anciennes versions ; aucune régression, aucune réécriture du pointeur courant |
| P01-K | Deux Pots indépendants, événements/transactions entremêlés | Monotonie et références garanties **par Pot**, sans imposer de synchronisation globale entre Pots |
| P01-L | Mesurer les reconstructions Pot@V et la résolution des composants, grand Pot et historique long | Mesures des requêtes, absence de N+1 pathologique, latence et allocations collectées ; comparaison aux budgets V3-5C, sans supposer une performance déjà atteinte |

### Méthode et preuves

JUnit avec PostgreSQL réel (transactions et connexions indépendantes, barrières de concurrence, injections de rollback). Capturer les snapshots WRITE de `Pot@V`, `changedComponents`, les références exactes de `CurrentPot`, les versions immuables persistées, le slot et l'issue des commits. Comparer à un oracle déterministe indépendant des projections READ, y compris pour les suppressions. Vérifier le contenu immuable, pas seulement le nombre de lignes.

**PASS PRJ-01** : P01-A à P01-L satisfaits, pointeur courant monotone et atomique, références homogènes, anciennes versions conservées sous arrivée hors ordre, aucun effet partiel ou historique fabriqué. Les consultations complètes restent à certifier en READ-01 et les soldes en PRJ-02. Aucun test V3 n'a encore été implémenté ou exécuté.

**Décision de V3-5E intégrée au protocole de preuve** : `CurrentPot` devient un pointeur monotone vers `PotVersion(P,V)` immuable complet en références, avec liste interne d'autorisation à V et références ordonnées de toutes les Expenses. Les descriptions antérieures de pointeur portant lui-même toutes les références sont **supplantées**. Les tâches Balance et Visibility demeurent indépendantes ; aucune nouvelle Consumption.

**PRJ-01 — preuves complémentaires :** chaque événement `POT_EVENT(P,V)` matérialise `PotVersion(P,V)` et les sous-versions exactes, puis avance le pointeur seulement si V est supérieur, **atomiquement en READ** avec complétion du slot ; une arrivée hors ordre doit matérialiser PotVersion ancienne sans régresser CurrentPot ; aucun changement de PotVersion existante ; cohérence des références locales et de la liste des utilisateurs autorisés à V ; pas de duplication des valeurs complètes.

**READ-01 — preuves complémentaires :** la lecture principale courante utilise pointeur→PotVersion→Shareholders/Balance de la même V, retourne en plus la liste complète des références des Expenses, sans leurs détails ; `minVersion` conserve sa signification ; la lecture complémentaire par groupes bornés de références versionnées à V respecte le manifeste de V même si CurrentPot avance, refuse toute référence absente/altérée, et n'assemble jamais plusieurs versions. Tester les courses V→V+1 entre pages, les dépendances momentanément manquantes et l'absence de fuite via les erreurs.

**SEC-01 — preuves complémentaires :** à chaque lecture, `CurrentBinding` actif et EUI réellement authentifiée, correspondance de `pocomaUserId` dans la liste **interne** `authorizedUserIds` (incluant `creatorId` et les utilisateurs liés aux Shareholders actifs à V) issue de `PotVersion(P,V)`. `CurrentUserPotList` n'est pas un oracle d'autorisation ; les références n'accordent aucun droit. Tester créateur non-Shareholder, Shareholder retiré après V, Unlink propagé, substitution d'Expense hors manifeste/pot et absence d'exposition de la liste des utilisateurs autorisés.

**PERF/OPS — preuves complémentaires :** taille de `PotVersion` et manifeste transmis sur 20/200 et 100/2 000, pression stockage/WAL sur 100, 1 000 et 10 000 versions, traitement V hors ordre, nombre de requêtes borné pour pages de 50 Expenses, impact sur latence E2E `minVersion` ; mesurer avant tout choix d'archivage/factorisation.

**Supras :** principes et statuts HTTP définis en SPEC-06 ; seuils physiques, enveloppes et paramètres de protection à qualifier avant certification. Ne pas revendiquer 24/24 preuves exécutées : seuls leurs protocoles existent.

**Contrat conceptuel arbitré ; réalisation V3-6 et certification V3-7 non exécutées.**

**Contrat de matérialisation directe :** aucune tâche de préparation, aucune tâche Balance/Visibility ; sept familles de sources, trois Consumptions non-burnables directes sur `POT_EVENT`, chacune Discovery + Engine autonomes. Les scénarios anciens qui exigent la création/terminalisation des tâches préparées doivent être **remplacés**, sans perdre leurs oracles : balances exactes et immuables pour chaque V, visibilité différentielle correcte et monotone, snapshot complet PotVersion et lecture cohérente à V.

**Tests à construire :** trois checkpoints distincts progressant indépendamment ; crash/retry/fencing de chacun ; absence d'ordre imposé entre Consumptions ; matérialisation V+1 avant V pour chacun ; visibilité V tardive ne régressant pas ; version Pot/Balance absente => lecture temporairement indisponible, pas de mélange ; visibilité indépendante ; WRITE@V reconstitué séparément par projecteur ; budgets SQL/CPU/WAL et latence E2E avant/après simplification sans prétendre une amélioration mesurée. Aucun test V3 exécuté.

### PRJ-02 — Protocole et oracles

**Statut : protocole proposé, non exécuté.** Source V3-1/V3-3 : pour **chaque** mutation effective du Pot portant sa version globale à V, calculer intégralement les soldes rationnels de `Pot@V` (y compris pour une mutation non financière) et matérialiser `PotBalance(potId,V)` en READ, **sans dépendre de `PotBalance(V−1)`**. `PotBalance` est immuable ; il n'existe ni projection CurrentBalance, ni chaîne d'indirections entre versions. La Consumption directe `MaterializePotBalance` reconstitue WRITE@V et calcule les soldes pour V dans sa propre tentative ; succès et suppression de son slot sont atomiques. À D terminale, elle termine sans insérer de Balance(D).

### Oracle métier indépendant

- Construire, pour chaque `Pot@V` de test, un **oracle de référence distinct de l'implémentation métier testée** : dépenses, contributeurs, bénéficiaires/quotes-parts, poids effectifs à V, associations et autres règles du domaine V3-1 ; utiliser des fractions exactes (entiers arbitraires pour numérateurs/dénominateurs ou une représentation équivalente prouvant l'exactitude), sans arrondi flottant.
- Vérifier **l'ensemble complet des utilisateurs/parts concernés**, les montants nets individuels et les invariants de conservation applicables ; comparer les valeurs rationnelles normalisées plutôt que les seuls formats textuels ou des égalités approchées. Les conventions de signe et les règles de répartition sont celles du contrat métier V3-1 ; ne pas en inventer d'autres dans les tests.
- Le résultat matérialisé porte **exactement (potId,V)**. Une balance V+1 ne peut remplacer ni modifier V et ne permet pas de conclure que V a été matérialisée ; une balance V existe indépendamment du pointeur mutable `CurrentPot`.
- Les calculs de V ne lisent pas `PotBalance(V−1)` comme condition nécessaire : injecter l'absence ou le retard de la version précédente et vérifier une production V correcte.
- Le calcul de la Consumption part de WRITE à **V exacte** ; pas d'échantillonnage de l'état courant WRITE lors d'une matérialisation tardive. Une mutation V+1 après préparation de V ne modifie pas les données de V.
- La validation finale de la Consumption, les écritures de `PotBalance(P,V)` et la suppression du slot sont atomiques (CON-04/06). Une duplication/reprise ne peut altérer une balance déjà durable.

### Scénarios déterministes

| ID | Stimulus / entrelacement | Oracle de réussite |
|---|---|---|
| P02-A | Pot V1 simple avec montants entiers et plusieurs bénéficiaires | Calcul complet des soldes attendus, rationalité exacte et équilibre global selon les règles du domaine |
| P02-B | Dépenses fractionnaires, nombres premiers dans les poids et montants, réductions de fractions | Aucun arrondi ou dérive numérique ; égalité exacte à l'oracle indépendant |
| P02-C | Changement de poids par défaut ou de poids explicites entre V et V+1 | Balance V conforme aux poids à V et Balance V+1 conforme aux poids à V+1 ; aucun emprunt des valeurs courantes |
| P02-D | Mutation non financière (libellé, métadonnée compatible) produisant une nouvelle V globale | Nouvelle `PotBalance(P,V)` **complète** et indépendante, même si les montants sont identiques à V−1 |
| P02-E | Création, modification, suppression d'Expense et changement de rattachement affectant la répartition | Chaque version conserve le calcul attendu ; suppressions et liens historiques respectés |
| P02-F | Traiter la Consumption Balance V+2 **avant** celles de V et V+1 | Les trois balances restent distinctes, correctes, immuables et accessibles en stockage ; aucune exigence de traitement FIFO |
| P02-G | Supprimer/retarder volontairement la balance V−1 puis traiter V | V est calculée et matérialisée correctement sans accès à V−1 |
| P02-H | Démarrer la Consumption de V, faire avancer WRITE jusqu'à V+1 puis matérialiser V | Le contenu enregistré reste celui de V exactement, non celui de l'état WRITE courant |
| P02-I | Deux Workers tentent de matérialiser le même `POT_EVENT(P,V)`, avec commit incertain et retry | Une seule valeur durable canonique `PotBalance(P,V)` ; identité/contenu inchangés ; slot finalisé une seule fois |
| P02-J | Erreur après insertion Balance mais avant suppression du slot | Rollback total ; pas de balance durable orpheline sans suppression de son slot |
| P02-K | `CurrentPot` pointe V+1 tandis que seule la balance V existe, puis balance V+1 arrive | Consultation complète à V+1 indisponible jusqu'à présence de sa balance exacte ; aucune substitution de V (READ-01) |
| P02-L | Deux Pots indépendants et plusieurs versions chacun, Consumptions entrelacées | Calculs et clés isolés par (P,V) ; aucune contamination entre Pots |
| P02-M | Profils petit (5/20), moyen (20/200), grand (100/2 000) et historiques de volumes V3-5C | Mesurer séparément reconstruction/préparation, attente et transaction de matérialisation ; p95 E2E des scénarios nominaux évalué contre les objectifs V3-5C, sans les présumer atteints |

### Robustesse de l'oracle et méthodes

JUnit et **PostgreSQL réel**, avec oracle rationnel indépendant, jeux de mutations connus, snapshots `Pot@V` attendus, captures de Consumptions, barrières de concurrence et injections de panne. Les tests de valeurs métier purs peuvent être unitaires ; l'immuabilité, les races et l'atomicité exigent PostgreSQL. Vérifier les clés et les **contenus** des anciennes balances après les traitements ultérieurs, non leur seule présence. Conserver les données génératrices (seed), les références et toutes les fractions de référence.

Pour les performances, ne pas confondre le **temps de calcul pur**, la préparation en WRITE/READ, l'enrôlement du slot, son attente et la disponibilité par API. La mesure E2E doit inclure la chaîne complète et respecter l'incertitude du sondage. Le budget p95 de 50 ms sur calcul/écriture des balances demeure **candidat**, tandis que les SLO globaux (500 ms Pot moyen nominal, 2 s grand Pot nominal) sont les objectifs validés.

**PASS PRJ-02** exige P02-A à P02-M, exactitude rationnelle, balance complète par version globale, immutabilité/idempotence et absence de dépendance à V−1. Les budgets sont qualifiés séparément en PERF-01 ; un succès fonctionnel ne certifie pas une performance. Aucun test V3 exécuté.

**DeletePot D :** la Consumption Balance termine avec succès sans insérer PotBalance(P,D) ; chaque V<D exige une balance exacte, y compris si elle est calculée après D.

### PRJ-03 — Protocole et oracles

**Statut : protocole proposé, non exécuté.** Références V3-3/V3-4 : chaque `POT_EVENT(P,V)` déclenche la Consumption autonome `MaterializePotVisibility`, qui recalcule depuis WRITE@V la **liste complète** des utilisateurs visibles à V. L'autorité de matérialisation est `PotVisibilityState(potId,materializedVersion)` : son verrou exclusif protège **l'ensemble** des entrées `CurrentUserPotList` de ce Pot et non un simple CAS individuel. Le calcul compare uniquement les entrées **actuellement visibles** (`visible=true`) à la liste entrante ; les tombstones `visible=false` sont conservées mais ne sont pas scannées pour établir la différence.

### Oracles de référence

- Pour chaque Pot et V, calculer un oracle indépendant `Visible(P,V)` : Pot actif et utilisateur créateur **ou** utilisateur rattaché à au moins un Shareholder ; Pot supprimé = ensemble vide. Plusieurs Shareholders liés à un même utilisateur ne produisent qu'une seule visibilité ; détacher un lien ne supprime pas l'accès si un autre subsiste.
- Un événement de version `V > materializedVersion` remplace l'ensemble visible courant par `Visible(P,V)` **atomiquement**, puis avance `PotVisibilityState.materializedVersion = V`. Les entrées nouvellement visibles sont créées/réactivées, celles devenues invisibles reçoivent une tombstone `visible=false`. Les entrées invisibles historiques déjà inchangées peuvent conserver leur `potVersion` antérieure : cette valeur est la dernière version de changement/évaluation enregistrée **pour l'entrée**, pas un indicateur global de fraîcheur.
- Un événement `V ≤ materializedVersion` est obsolète : **aucun changement** des entrées ni recul de l'état ; elle est néanmoins terminalisée et son slot supprimé de façon atomique.
- Toute mutation de la liste de visibilité d'un Pot participe au **même verrou collectif** PotVisibilityState, y compris le cas de création initiale ; deux initialisations concurrentes ne peuvent créer deux autorités ni produire d'insertion fantôme.
- La projection sert à la **navigation** ; elle n'est jamais l'autorité d'accès à un Pot. Les tests de sécurité correspondants sont couverts par SEC-01.
- La transaction READ protège ensemble la lecture des `visible=true`, le différentiel, les écritures/tombstones, la progression de PotVisibilityState, la terminalisation de le consommable et la suppression de son slot. La liste ne doit pas exposer d'état partiellement mis à jour après commit.

### Scénarios PostgreSQL déterministes

| ID | Stimulus / entrelacement | Oracle de réussite |
|---|---|---|
| P03-A | Première Consumption V1 pour un Pot sans ligne de contrôle préexistante | Initialisation sûre de PotVisibilityState ; ensemble visible exactement égal à `Visible(P,V1)`, aucune entrée double |
| P03-B | Deux Workers tentent simultanément la première initialisation du même Pot, avec listes/version différentes | Une seule autorité de verrou, évolution sérialisée ; état final à la plus haute version committée ; aucun utilisateur fantôme |
| P03-C | Un utilisateur devient visible, disparaît, puis réapparaît (V1→V2→V3) | Même entrée `(user,pot)` conservée avec `true→false→true` et versions adéquates ; pas de suppression physique à V2 |
| P03-D | Un utilisateur a deux Shareholders ; l'un est détaché, puis le dernier | Visible après premier détachement, invisible seulement après le dernier ; déduplication par user |
| P03-E | Créateur sans Shareholder lié, puis Pot supprimé | Créateur visible tant que Pot actif, puis toutes les entrées visibles deviennent `false` après suppression |
| P03-F | Matérialiser le consommable V3 avant V1 et V2 | Version du contrôle reste V3 et ensemble exactement `Visible(P,V3)` ; les Consumptions V1/V2 se terminent sans régression ni écriture de tombstones tardives |
| P03-G | Deux Consumptions de versions V et V+1 se concurrencent, avec barrières avant verrou collectif | Verrou unique sérialise lecture, diff et mise à jour ; résultat final conforme à la version maximale, aucune insertion fantôme |
| P03-H | Nouvelle liste vide après liste non vide, puis nouvelle liste non vide | Toutes les visibles passent à `false` ; seules les anciennes visibles sont relues ; réactivation ultérieure correcte |
| P03-I | Historique volumineux de tombstones, avec peu d'utilisateurs actuellement visibles | Le calcul de diff ne parcourt pas toutes les entrées `false` ; plan SQL/mesures le démontrent sans compromettre l'exactitude |
| P03-J | Panne après écriture de certaines lignes, après avancée du contrôle ou après terminalisation de Consumption, avant commit | Rollback de l'ensemble : entrées, contrôle, Consumption et slot restent cohérents ; aucune demi-projection |
| P03-K | Commit validé mais réponse perdue, suivi d'une réacquisition/duplication de tentative | Résultat idempotent, slot finalisé une fois ; aucune régression ni écriture supplémentaire |
| P03-L | Deux Pots distincts avec Consumptions parallèles | Sérialisation **par Pot**, pas de verrou global de visibilité ; ensembles isolés |
| P03-M | Entrée de liste incorrectement visible, mais Shareholder absent à Pot@V | La navigation ne confère aucun accès : READ refuse selon SEC-01 ; le test ne confond pas liste et autorisation |
| P03-N | Lectures concurrentes pendant matérialisation d'une liste changeant pour plusieurs utilisateurs | Un lecteur observe l'ancien ou le nouvel ensemble committé, jamais un mélange partiellement committé |
| P03-O | Grand Pot et historique important, forte alternance des membres | Mesurer lignes `visible=true` lues, coût du verrou, mutations, requêtes/latences ; pas de scan complet de l'historique des utilisateurs |

### Méthode et preuves attendues

JUnit avec **PostgreSQL réel** : jeux `Visible(P,V)` établis indépendamment des projections, connexions concurrentes, barrières de prise du verrou, fault injection et comparaison complète des entrées de navigation et de la version de contrôle. Examiner les requêtes/plans PostgreSQL pour confirmer que l'algorithme parcourt les seules entrées actuellement visibles lors du différentiel, sans balayage de toutes les tombstones. Vérifier les résultats depuis une connexion indépendante après chaque commit/rollback.

**Attention à la précision :** l'avancement du contrôle de V1 à V3 peut sauter V2 ; il signifie que la visibilité a été recalculée **entièrement à V3**, pas que chaque Consumption intermédiaire a été exécutée. Cela reste correct parce que la liste d'utilisateurs visibles est fournie **intégralement** par chaque Consumption. Le `potVersion` d'une ligne inchangée peut rester inférieur à `materializedVersion` sans incohérence.

**PASS PRJ-03** : P03-A à P03-O, résultat visible exact et monotone, protections collectives sur toutes les écritures, concurrence/rollback sûrs, performances du différentiel observées et aucune confusion avec les droits d'accès. Aucun test V3 n'a été exécuté.

## 8. READ et sécurité

### READ-01 — Protocole et oracles

**Statut : protocole proposé, non exécuté.** READ fournit les consultations courantes sans appel synchrone à WRITE. Les projections évoluent indépendamment ; aucun snapshot global entre Pots n'est promis. Pour une demande avec `minVersion=m`, la consultation sélectionne une fois une version courante V de `CurrentPot` avec `V ≥ m` et doit résoudre **toutes** les références de sous-objets et `PotBalance(P,V)` nécessaires au contenu demandé. Une réponse ne doit jamais mélanger les données de deux versions globales. La visibilité de liste, les résultats et les bindings ne constituent pas une barrière globale de disponibilité.

### Oracles contractuels

- Contrôler d'abord authentification, Binding et autorisation démontrable dans READ selon SPEC-04 §10 et SPEC-06 §§9–10 : Pot absent de READ, terminal ou non autorisé => `404` uniforme. Pour un Pot actif matérialisé et autorisé, `CurrentPot.version < minVersion` ou dépendance nécessaire absente => `503` temporaire. Une panne globale READ peut également rendre `503`, sans révéler de ressource non autorisée.
- Si `CurrentPot(P,V)` existe, capturer **une seule fois V et ses références**. Une consultation nécessitant la balance ne peut réussir que si `PotBalance(P,V)` existe, est complète et appartient exactement à V ; même règle pour chaque `SubobjectVersion` référencée. Si un élément nécessaire manque, rendre temporairement indisponible plutôt qu'une réponse partielle ou un substitut V−1/V+1.
- Une consultation qui ne requiert pas une projection donnée ne doit pas attendre artificiellement sa matérialisation (par exemple un résultat terminal consultable peut précéder CurrentPot ; une lecture d'en-tête ne doit pas nécessairement attendre les balances si son contrat n'en a pas besoin).
- Un déplacement concurrent de CurrentPot de V à V+1 après capture ne peut pas transformer la réponse en assemblage hybride. Les valeurs immuables de V restent lisibles pendant la requête ; assurer snapshot SQL adapté ou lecture cohérente par références figées, à démontrer.
- `minVersion` est un **plancher de fraîcheur**, pas l'exigence de servir exactement m. Si V > m et tous les composants de V sont prêts, la réponse peut servir V ; elle ne prétend pas exposer un historique arbitraire à m.
- La visibilité ne constitue **jamais** une barrière de complétude du Pot principal ; PotVersion, références de Shareholders et Balance de la version active servie sont nécessaires.
- Pour plusieurs Pots, les versions servies peuvent différer, même dans une liste d'utilisateur ; aucune photographie globale transactionnelle n'est promise.

### Cas déterministes

| ID | Stimulus | Oracle de réussite |
|---|---|---|
| R01-A | Pot absent dans READ, puis projection initiale apparue | Avant matérialisation, `404` faute d'autorisation démontrable depuis READ ; après matérialisation, succès complet uniquement si autorisé ; aucun accès WRITE synchrone |
| R01-B | CurrentPot actif V4, utilisateur autorisé à V4, requête `minVersion=5` | `503` temporaire après autorisation ; aucune réponse V4 présentée comme satisfaisant V5 |
| R01-C | CurrentPot à V6 et `minVersion=5` | V6 peut être servie si complète et autorisée ; pas d'exigence artificielle de servir V5 |
| R01-D | CurrentPot V6 référence plusieurs sous-versions dont l'une est absente (injection de faute en base READ) | Lecture complète indisponible (`503` après autorisation) jusqu'à présence de toutes les références ; aucune suppression de champ silencieuse |
| R01-E | CurrentPot V6 et Balance(P,V5) présente, Balance(P,V6) absente | Consultation nécessitant Balance indisponible ; ne jamais réutiliser le solde V5 |
| R01-F | CurrentPot V6 et Balance(P,V7) présente, Balance(P,V6) absente | Consultation nécessitant Balance indisponible ; ne jamais emprunter un solde futur |
| R01-G | CurrentPot passe V6→V7 pendant une lecture ayant capturé V6 | Réponse cohérente V6 ou indisponibilité ; jamais assemblage V6/V7 |
| R01-H | CommandResult de la commande cible présent, mais CurrentPot cible pas encore à V | CommandResult consultable indépendamment ; lecture Pot avec `minVersion=V` encore indisponible |
| R01-I | CurrentPot et composants prêts, mais Consumption Balance ou Visibility en retard | Seules les lectures dont les composants requis manquent sont indisponibles ; test E2E « toutes projections » attend séparément les jalons Balance et Visibility |
| R01-J | Deux Pots avec projections et versions décalées dans CurrentUserPotList | Navigation sans faux snapshot inter-Pots ; toute ouverture de Pot réévalue son autorisation et sa complétude propre |
| R01-K | Changement de rattachement Shareholder entre deux versions ; demande authentifiée | Autorisation appréciée selon le Shareholder de la **version réellement servie**, jamais sur liste de navigation ou snapshot WRITE courant |
| R01-L | Projection CurrentPot plus récente que la cible d'une commande déjà achevée | `minVersion` est satisfait par V supérieure, si consultation V supérieure complète ; ne pas prétendre que Pot courant restitue automatiquement l'ancienne V cible |
| R01-M | Concurrent readers pendant commit/rollback de matérialisation partielle de sous-objets / CurrentPot | Lecteurs ne voient que données committées et complètes, ou indisponibilité explicite ; aucun état transitoire partiellement écrit |
| R01-N | Vérifier instrumentation client de sondage READ avec retards artificiellement contrôlés | Mesure E2E fondée sur première réponse réussie prouvant les composants requis ; retard de polling quantifié ; ne pas qualifier cette borne de timestamp exact du commit |

### Méthode et PASS

JUnit sur PostgreSQL réel, incluant interventions précises sur l'ordre des matérialisations et les transactions concurrentes, complété par Bruno sur l'API authentifiée. Comparer l'état retourné à un oracle de reconstruction autoritative Pot@V et aux versions locales référencées ; vérifier chaque composant et balance, pas uniquement le `potVersion`. Vérifier les réponses `404` pour absence/non-autorisation et `503` pour retard après autorisation selon SPEC-06, puis la convergence lorsque les éléments arrivent. Conserver les versions/IDs, états de slots et Consumptions, snapshots SQL et captures des réponses API.

**PASS READ-01** : R01-A à R01-N satisfaits ; aucun Pot partiel, aucune version mélangée, interprétation correcte de `minVersion`, indépendance des projections et des Pots ; pas de lecture WRITE synchrone. L'authentification et l'autorisation sont qualifiées avec SEC-01/02. Aucun test V3 exécuté.

**SUPRA-READ-01, contrat normatif de SPEC-06 — §§4–5** : le front découpe le manifeste `PotVersion(P,V)` en groupes ; le Supra READ impose une limite de références par lot, vérifie EUI/binding actif, autorisation à V et appartenance au manifeste, résout les `SubobjectVersion` nécessaires et répond **tout ou erreur**, sans troncature ni recours à WRITE. Aucun deuxième système de pagination serveur imposé.

**Tests bloquants avant qualification** : limites 0/1/N et plafond−1/plafond/plafond+1, doublons, références étrangères à V/Pot, versions de sous-objets substituées, Unlink et Shareholder lié à V, CurrentPot V+1 pendant lecture V, dépendance absente, grosse Expense conduisant au plafond de réponse, ordre et complétude du lot, latence et nombre de requêtes SQL pour 50 Expenses/2 000 références.

**Réserves :** seuil numérique de lot et budget d'octets à qualifier conformément à SPEC-06. Les statuts HTTP suivent SPEC-06 §§7–10 ; protocoles non exécutés, sans réouverture du cadrage.

### SEC-01 — Protocole et oracles

**Règle prioritaire :** EUI authentifiée et bindingToken congruent à un CurrentBinding actif, puis droit du créateur immuable ou d'un Shareholder rattaché au même PocomaUser à la **version demandée V**. Aucun droit actuel à une version supérieure n'est nécessaire. Navigation indépendante. Après autorisation : minVersion et dépendances peuvent causer 503 ; absent, non autorisé, terminal D ou version non visible en READ donnent 404 uniforme. Une panne globale READ peut donner 503. Pas de lecture WRITE synchrone.

### Cas déterministes

| ID | Stimulus | Oracle de réussite |
|---|---|---|
| S01-A | EUI authentifiée E1 + bindingToken actif de E1 + User de E1 autorisé dans Pot@V (créateur `creatorId` même sans Shareholder, ou Shareholder de Pot@V lié au User de E1) | Lecture autorisée, complète sous READ-01 |
| S01-B | Aucun JWT/identité authentifiée, mais bindingToken valide connu | Accès refusé : token seul insuffisant |
| S01-C | EUI E2 authentifiée présente le bindingToken de E1 | Refus, y compris si E2 connaît le Pot et son identifiant |
| S01-D | EUI E1 authentifiée présente token inventé, expiré/remplacé ou appartenant à EUI liée à un autre User | Refus ; aucune résolution approximative par userId ou token seul |
| S01-E | EUI authentifiée correcte, CurrentBinding absent, inactif ou sans pocomaUserId | Refus / absence de liaison, sans accès au Pot |
| S01-F | EUI et bindingToken corrects, mais User ni créateur (`creatorId`) ni lié à aucun Shareholder dans Pot@V | Refus (`404`) malgré Pot connu ou liste de navigation trompeuse |
| S01-G | Rattachement Shareholder présent à V puis retiré à V+1, avec lectures à versions réellement servies distinctes | Droit évalué sur la version servie ; accès admis à V si cette consultation est disponible, refus à V+1 si aucun lien restant |
| S01-H | User lié à deux Shareholders, puis détaché de l'un | Droit maintenu tant qu'au moins un Shareholder de la version servie reste lié |
| S01-I | CurrentUserPotList contient visible=true erroné ou périmé pour un User sans droit à Pot@V | Aucune autorisation par la liste ; ouverture du Pot refusée |
| S01-J | CurrentUserPotList ne contient pas le Pot alors que Shareholder lié et CurrentBinding valide | Accès direct au Pot autorisé si READ-01 satisfait ; navigation non autoritative |
| S01-K | Appel fournit un pocomaUserId arbitraire, ou tente de le modifier dans le payload/paramètre | Identité ignorée comme autorité ou entrée refusée ; User dérivé seulement du binding authentifié |
| S01-L | Command/IdentityResult en READ disponible avant matérialisation de CurrentBinding ou Pot | Pas de contournement des vérifications pour ouvrir le Pot ; latence de projection traitée indépendamment |
| S01-M | Test d'isolation entre deux tenants/émetteurs : même `subject` chez deux `issuer` | Deux EUI distinctes ; pas de confusion d'autorité malgré `subject` identique |
| S01-N | Requêtes concurrentes pendant changement de CurrentPot V→V+1 ou CurrentBinding version supérieure | Autorisation cohérente avec binding et version effectivement observés, sans mélange non maîtrisé ; jamais de droit obtenu par état partiel |
| S01-O | Parcours Keycloak réel : jeton signé et validé, EUI extraite puis bindingToken vérifié | Identité authentifiée effectivement reliée au bon binding ; refus des jetons falsifiés, mauvais issuer/audience, expirés selon politique OIDC applicable |

### Méthode et critères PASS

Combiner **Bruno** (API, erreurs, contrôle de non-divulgation), **JUnit avec PostgreSQL réel** (versions, rattachements, état CurrentBinding, races et absence de navigation autoritative) et **Keycloak réel** pour les parcours OIDC dans la configuration B. Utiliser plusieurs identités EUI et Pots aux historiques divergents. Vérifier pour chaque refus l'absence de contenu protégé dans réponse et logs d'application observables ; ne pas journaliser les tokens. Couvrir à la fois la lecture courante (`GET /pots/{potId}`) et la lecture historique explicite à une version `V < D` (`SPEC-06 §4`, `A11-F`).

**PASS SEC-01** : S01-A à S01-O, absence de confusion EUI/token, authentification indispensable, contrôle des droits à V (`creatorId` ou Shareholder actif à V), aucun privilège issu de CurrentUserPotList et aucune fuite de contenu lors des refus. SEC-02 teste séparément la fenêtre acceptée de propagation d'Unlink. Aucun test V3 n'a été implémenté ni exécuté.

### SEC-02 — Protocole et oracles

**Statut : protocole proposé, non exécuté.** V3-3/V3-4 acceptent explicitement la fenêtre pendant laquelle une opération `Unlink` est committée dans WRITE, mais sa projection `CurrentBinding` n'est pas encore mise à jour dans READ. L'accès READ demeure gouverné par **l'EUI authentifiée et son bindingToken effectivement actif dans CurrentBinding READ**, puis les droits Shareholder dans la version Pot servie. Cette tolérance de propagation **ne s'étend pas à WRITE** : ses commandes et demandes Identity relisent les autorisations de l'état WRITE actuel à l'exécution.

### Oracles contractuels

- Un `Unlink` gagnant termine atomiquement en WRITE avec événement `IDENTITY_EVENT(EUI,targetVersion)`, résultat et complétion ; il n'impose pas un commit synchrone de `CurrentBinding` dans READ.
- Tant que READ porte encore l'ancienne version active, l'ancien couple **EUI authentifiée + bindingToken** peut rester utilisable pour une lecture qui satisfait aussi les droits Pot@V. C'est une **fenêtre acceptée**, non une garantie qu'une lecture réussisse pendant ce délai.
- Dès que `CurrentBinding(EUI).identityVersion ≥ targetVersion` matérialise l'Unlink, le token précédent ne donne plus accès à READ. Un événement Identity antérieur arrivant ensuite ne doit pas restaurer le lien ni le token.
- Une requête WRITE exécutée après l'Unlink ne doit pas s'autoriser à partir d'un `CurrentBinding` périmé ; elle contrôle son état autoritatif WRITE au moment de l'exécution. Les demandes déjà admises suivent le contrat d'attestation à admission et de validations Pocoma au moment de l'exécution (ID-02).
- `IdentityRequestResult` et `CurrentBinding` progressent indépendamment ; voir le résultat d'Unlink ne démontre pas que READ a révoqué le token.
- Une Registration ou un Link ultérieur vers la même EUI ne réactive pas implicitement l'ancien token ; l'autorisation exige le nouveau binding exact après projection. Un `minVersion` atteint peut désigner une version Identity ultérieure, sans garantir que le rattachement supprimé existe encore.
- Les scénarios documentent explicitement le **risque de propagation différée accepté** ; aucune prétention de révocation immédiate ou de durée maximale non validée n'est introduite.

### Cas déterministes

| ID | Stimulus / entrelacement | Oracle de réussite |
|---|---|---|
| S02-A | EUI liée, token actif et lecture Pot autorisée avant Unlink | Baseline : accès possible avec identité authentifiée correspondante et Shareholder à la version lue |
| S02-B | Unlink committé en WRITE ; bloquer la Consumption Identity→CurrentBinding | WRITE EUI détachée ; READ garde ancien binding ; ancienne lecture peut encore passer, sans être revendiquée comme toujours possible |
| S02-C | Après Unlink WRITE, tenter une mutation qui exige le binding encore actif avec l'ancien token | Contrôle WRITE courant rejette ou applique la décision conforme au domaine ; aucun droit indu par CurrentBinding en retard |
| S02-D | IdentityRequestResult(Unlink) visible avant la matérialisation de CurrentBinding | Le résultat indique succès/targetVersion mais ne suffit pas à prouver révocation READ ; les branches restent indépendantes |
| S02-E | Matérialiser `IDENTITY_EVENT` cible et observer CurrentBinding à targetVersion | Ancien token inopérant immédiatement après cette matérialisation pour toute nouvelle lecture READ |
| S02-F | Traiter l'événement EUI de version antérieure après celui d'Unlink | CurrentBinding reste monotone et révoqué ; pas de restauration par livraison tardive |
| S02-G | Relinker ultérieurement EUI avec nouveau token, projeter la nouvelle version | Ancien token toujours refusé ; nouveau token utilisable seulement avec EUI authentifiée correspondante et droits Pot |
| S02-H | EUI liée à plusieurs situations d'accès Pot, puis Unlink | Après propagation, toutes les lectures dépendant de ce binding refusées, indépendamment d'une ancienne entrée CurrentUserPotList |
| S02-I | Deux EUI distinctes liées à un même PocomaUser ; unlink d'une EUI seulement | Ancienne EUI révoquée en READ après propagation ; autre EUI et son token restent utilisables selon leurs droits |
| S02-J | Accès en concurrence avec transaction de matérialisation CurrentBinding, y compris rollback injecté | Aucune ligne partiellement mise à jour ; lecture évaluée sur état READ committé ; rollback ne provoque pas de fausse révocation durable |
| S02-K | Mesure bout en bout Unlink admission→WRITE terminal→IdentityResult visible→CurrentBinding révoqué→lecture refusée | Chronologie des jalons observable sans confondre résultats, commits et sondages ; ne pas ajouter de SLO de révocation inexistant |

### Méthode, précautions et PASS

JUnit avec **PostgreSQL réel** pour contrôler commits, ordres d'événements, projections et rollbacks ; **Bruno + Keycloak réel** pour l'identité authentifiée et les lectures avant/après matérialisation. Maintenir un Pot test avec Shareholder lié et projections déjà complètes, afin de ne pas confondre révocation Identity et retard Pot. Vérifier CurrentBinding, IdentityRequestResult, versions, token ancien/nouveau, résultats des lectures et refus WRITE. Les tokens ne doivent pas être inscrits dans les logs/rapports exposés : utiliser des identifiants anonymisés et des assertions privées.

**PASS SEC-02** : S02-A à S02-K satisfaits ; fenêtre de propagation acceptée explicitement, refus garanti après matérialisation effective d'Unlink, monotonie malgré événements hors ordre, indépendance WRITE/READ et absence d'autorité de la liste de navigation. Le p95 ≤ 500 ms validé en V3-5C concerne **Registration/Link jusqu'au binding utilisable**, pas Unlink jusqu'à révocation ; aucun SLO de révocation n'a été fixé. Aucun test V3 exécuté.

**Contrat :** lecture de `CurrentBinding` par l'EUI authentifiée exclusivement, sans bindingToken entrant ; seul son token actif peut être renvoyé. `IDENTITY_EVENT(EUI,V)` déclenche une matérialisation lisant Binding WRITE à **V**, sans transport du token dans l'événement.

**Preuves à réaliser :** (A) Registration E1 puis `me/binding?minVersion=V`, restitue uniquement token E1 ; (B) Link initié E1 vers E2 puis succès visible E1 sans exposition de token E2 ; (C) session E2 consulte son token E2 uniquement ; (D) usurpation via EUI/target choisie interdite ; (E) token périmé, Unlink et retard READ avec `minVersion` ; (F) replay hors ordre d'IDENTITY_EVENT et Binding reconstruit à la bonne version ; (G) absence de token dans événement partagé, logs, cache partagé et corps d'erreur. Prévoir Bruno + JUnit/PostgreSQL ; aucun essai V3 exécuté.

## 9. Supras et admission

### ADM-01 — Contrôle d'entrée

Contrat normatif : [SPEC-06 — §2](SPEC-06_Supra_Contracts.md). Rejet autonome à l'entrée sans lire WRITE métier ; aucun état durable pour requête refusée ; admission unique des demandes admissibles ; validation métier maintenue au Worker. Intégré aux 27 exigences actives de la matrice §2.

**Oracles de qualification :** refus avant persistance des tailles et structures aberrantes ; zéro événement/slot/résultat ; acceptation des limites exactes ; idempotence/concurrence ; rejet métier différé de cible inexistante, obsolète ou interdite ; authentification/consentement Link ; contrôle des limites de décompression et de cardinalité ; modification incrémentale d'une règle sans rétroactivité sur demandes déjà admises. JUnit/Bruno pour la sémantique, charge ciblée pour consommation de ressources.

**Réserves à arbitrer avant V3-CADRE :** politique d'erreurs et de plafonds configurables, bornes numériques concrètes, application des contrôles communs et spécifiques selon la nature des opérations. Aucune campagne exécutée.

### S-01 — Pagination CurrentUserPotList

**Décision adoptée SPEC-06 — §6 :** keyset `potId` croissant sur `CurrentUserPotList` visible pour l'utilisateur résolu depuis `CurrentBinding`, curseur opaque et **pas de snapshot** entre pages. Protocole à construire : pages stables sans doublon ; insertion visible avant curseur omise, après curseur éventuellement intégrée ; retrait de visibilité ; curseur altéré ; plafond serveur ; changement de binding ; isolation des utilisateurs et plans PostgreSQL sous grand nombre d'entrées. Aucun test V3 exécuté.

### S-02 — Manifeste complet

**Contrat décidé SPEC-06 — §5 :** réponse principale authentifiée/autorisé à V contenant en-tête, tous Shareholders, soldes exacts et **toutes les références ordonnées d'Expenses**, sans détail des Expenses ni pagination supplémentaire des références. Les chargements groupés des détails restent bornés par le serveur.

**Preuves :** complétude du manifeste pour 0/200/2 000 Expenses et au-delà selon jeux pertinents ; aucune fuite des `pocomaUserId` internes ; aucun mélange V/V+1 ; liste de références et soldes cohérents à V ; taille JSON/latence/CPU/mémoire pour grand Pot ; réponse complète ou erreur explicite, sans troncature ; absence d'interprétation du plafond 1 Mio des lots de détails comme seuil du manifeste principal. Aucun test V3 exécuté.

### S-03 — Idempotence des admissions

**Contrat validé SPEC-06 — §2 :** clé client liée à identité/portée et intention canonique ; première admission durable atomique avec l'association ; réitération identique retrouve la même demande ; intention différente sous même clé donne 409, sans nouvelle admission. La clé ne constitue pas une autorisation, et ne remplace pas la preuve de consentement Link.

**Preuves JUnit/PostgreSQL et Bruno :** course de deux admissions, contrainte unique et atomicité ; rollback, commit à issue inconnue puis retry, avant/après résultat terminal ; comparaison des intentions et JSON équivalents ; changement de limites entre admission et retry ; portées d'identité distinctes ; Link preuve d'octroi et cible non substituable. Exiger zéro doublon Command/IdentityRequest, aucun événement de mutation supplémentaire du seul fait du retry et aucune divulgation de l'intention d'autrui. Durée de rétention et codes nominaux à fixer avant exécution ; aucun test V3 lancé.

### AUD-V3-010 — Résultats et polling (A10-A à A10-K)

**Portée :** `CommandResult`, `IdentityRequestResult`, admission, complétions, READ asynchrone et Supra ; aucun runtime, DDL ni test V3 réalisé pendant l'audit. Vérifier sur PostgreSQL réel et via HTTP authentifié, avec EUI distinctes (même PocomaUser ou Users différents), que la provenance est immuable depuis l'admission et que READ n'interroge pas WRITE en consultation.

| ID | Scénario | Oracle |
|---|---|---|
| A10-A | Command admise par E1 puis succès | Demande WRITE stocke E1, Consumption lit demande + terminal WRITE, résultat READ stocke E1 ; E1 obtient `200` |
| A10-B | Command admise par E1 puis rejet `bindingToken` invalide | E1 demeure propriétaire, aucun PocomaUser nécessaire pour autoriser le résultat, E1 obtient `200` et `REJECTED` |
| A10-C | Registration E1 réussie ou rejetée sans Binding initial | E1 obtient son résultat par EUI authentifiée, sans token ni User requis |
| A10-D | Link initié par E1 vers E2 (avec consentement séparé de E2), puis Unlink E1 ou E2 initié par E1 | Les résultats appartiennent à E1, non à E2, y compris pour succès ou rejet ; E2 reçoit le même `404` qu'un requestId inconnu |
| A10-E | E1 et E3 liées au même PocomaUser | E3 ne consulte jamais les résultats de E1, malgré le User commun |
| A10-F | E1 Unlink puis re-Link vers autre PocomaUser | E1 authentifiée retrouve ses anciens résultats ; ni ancien ni nouveau Binding n'est requis à la lecture |
| A10-G | EUI tierce connaît un requestId valide, et UUID inexistant | Codes et enveloppes publiques `404` indiscernables, aucun potId, motif de rejet ou EUI divulgués |
| A10-H | Demande admise `202`, encore PENDING WRITE ; terminale WRITE mais résultat READ retardé ; puis matérialisée | `404`, `404`, puis `200` pour l'EUI initiatrice ; client continue polling borné sans interpréter `404` comme échec métier |
| A10-I | Service READ réellement indisponible ; appel non authentifié | `503` en véritable indisponibilité, `401` sans AuthN ; absence normale de ligne ne déclenche jamais `503` |
| A10-J | Compétition/retry de Consumption et événements de complétion minimaux | Résultat READ unique/atomique et `requesterEui` correct ; événement sans EUI dupliquée ; aucun appel WRITE synchrone de la Supra READ |
| A10-K | Relecture ultérieure d'un résultat sans expiration fonctionnelle | Même résultat immuable, aucun statut `EXPIRED`, aucun effacement automatique dans le modèle V3 actuel |

**Preuves attendues :** états WRITE de demande/résultat et READ de résultat, traces de Consumption, assertions HTTP sur deux EUI, contrôles d'absence de requête WRITE de la Supra READ, ordre forcé entre complétion et projection, et scénarios négatifs anti-énumération. Les questions opérationnelles de conservation/idempotence restent distinctes. **PASS uniquement après exécution réelle**, non revendiqué ici.

### AUD-V3-011 — DeletePot et HTTP (A11-A à A11-N)

**Protocole conceptuel ; scénarios définis, non exécutés.** Construire un Pot P avec créateur C, Shareholders associés à A/B, état actif à V8 et suppression par C à D=V9. Vérifier séparément WRITE, chacune des trois Consumptions READ et les réponses HTTP. Ne pas ajouter de projection ni d'endpoint de test.

| Cas | Stimulus | Oracle |
|---|---|---|
| A11-A | `DeletePot` appliqué après V8 | Seul PotHeader devient `deleted=true` à D ; commande terminale, aucune mutation métier post-D ; historique V1..V8 inchangé. |
| A11-B | `MaterializePotVersion(P,D)` | `PotVersion(P,D)` durable, `CurrentPot(P) >= D` monotone ; version D non consultable via API métier. |
| A11-C | `MaterializePotBalance(P,D)` | Consumption et slot terminent normalement **sans** `PotBalance(P,D)` ; absence intentionnelle non assimilée à erreur/retard. |
| A11-D | Exécuter l'événement Balance V8 **après** D | `PotBalance(P,V8)` exacte et durable ; aucun skip des anciennes versions du fait de l'état supprimé courant. |
| A11-E | `MaterializePotVisibility(P,D)` lente ou hors ordre | Le Pot devient invisible pour tous après progression de la visibilité ; aucune dépendance artificielle de CurrentPot/Balance à Visibility. |
| A11-F | Créateur ou Shareholder autorisé à V7/V8 après suppression D | Lecture historique V<D `200` et références Expense autorisées à V ; pas d'exigence de droits sur D ni de présence dans la liste. |
| A11-G | Lecture courante de P et lecture historique exacte D après matérialisation | `404` uniforme, même pour le créateur ; aucun `503` pour Balance(D) manquante volontairement. |
| A11-H | Tiers non autorisé, Pot inexistant, Pot supprimé, `minVersion=999999` | Absence et refus indiscernables (`404`), sans `503` précoce, ni information de version par recherche dichotomique. |
| A11-I | Version active autorisée mais `minVersion` au-delà de CurrentPot ou dépendances Balance/Subobject manquantes | `503` seulement **après** autorisation READ prouvée ; pas de réponse partielle ni de lecture WRITE synchronique. |
| A11-J | Pot créé dans WRITE mais encore absent de READ | `404` jusqu'à preuve READ des droits, puis lecture normale / `503` de fraîcheur si autorisé et non complet ; aucun indice d'existence WRITE. |
| A11-K | Droits Shareholder perdus après V8 ; Binding actif ou Unlink propagé | Droit historique à V8 conservé si Binding actif ; refus après révocation de Binding matérialisée ; fenêtre asynchrone conforme SEC-01/02. |
| A11-L | Command `DeletePot` avec `resultingPotVersion=D` | Latence depuis admission jusqu'à observation interne READ `CurrentPot -> PotVersion(P,D).deleted=true`, mêmes budgets C-03 ; `404` HTTP seul n'est pas un succès de propagation. |
| A11-M | Charges nominale/soutenue/pointe et Grand Pot, suppression | Budgets C-03 du jalon alternatif ; Balance(D) non attendue, balances actives antérieures et progression Visibility vérifiées distinctement. |
| A11-N | Lecture des sous-objets et Expense batch à V antérieure depuis références légitimes ou falsifiées | Autorisation à V, appartenance au manifeste, refus 404 uniforme pour références étrangères ou hors V ; aucun accès via simple connaissance d'ID. |

**Critère de certification :** démontrer exactement la suppression terminale, l'accessibilité historique autorisée, l'absence légitime de Balance(D), l'indépendance/hors ordre des Consumptions et l'ordre anti-énumération 401/404/503. Les tests PRJ-01, PRJ-02, READ-01, SEC-01, SEC-02, PERF-01 et SUPRA-READ-01 sont amendés en conséquence. Les anciens critères exigeant Balance à **chaque** version ou lecture HTTP `200` après `DeletePot` sont supplantés. Aucun test V3 lancé.

## 10. Reconstruction

### REC-01 — Protocole et oracles

**Statut : protocole proposé, non exécuté.** V3-4 retient deux opérations différentes : **reconstruction totale de READ à blanc**, avec arrêt des writers READ, reset cohérent et réutilisation possible des mêmes `consumptionId` ; **rejeu indépendant**, défini comme une nouvelle Consumption portant un nouvel ID et des effets dont la destination/coexistence est explicitement maîtrisée. Aucune remise à zéro isolée des checkpoints ou slots n'est sûre. Les événements immuables WRITE restent disponibles sans purge dans le périmètre V3.

### Invariants de reconstruction

- **Barrière opérationnelle :** avant le reset, empêcher toute nouvelle acquisition Discovery/Engine READ et attendre/neutraliser les écritures READ en cours ; une transaction READ ancienne ne peut pas committer après le reset en réinjectant des effets périmés. Pendant la reconstruction, les APIs ne doivent pas présenter READ partiellement reconstruit comme complet et prêt. La remise en service exige un critère explicite de disponibilité.
- **Ensemble cohérent :** projections READ, slots, claims/leases de partition READ et checkpoints sont réinitialisés de manière coordonnée. Les définitions et IDs des Consumptions réutilisées restent stables. Les événements immuables de WRITE et les demandes autoritatives ne sont pas effacés.
- **Reconstruction à mêmes IDs :** les checkpoints non-burnables repartent de zéro dans un espace READ entièrement réinitialisé. Les sources terminales WRITE ne sont **pas** rouvertes : READ redécouvre les cinq séquences d'événements immuables, pas les COMMAND/IDENTITY_REQUEST déjà terminales.
- **Consommations indépendantes :** leur finalisation n'exige pas d'ordre global ; un jalon de remise en service doit néanmoins attester les projections nécessaires aux parcours annoncés. Le rattrapage de CurrentPot ne suffit pas à lui seul à établir que les balances, visibilité, binding et résultats sont prêts.
- **Rejeu indépendant :** un nouveau `consumptionId` produit des slots et checkpoints propres. Les projections existantes ne doivent pas être modifiées de façon incohérente par une deuxième génération d'écrivains ; leur destination, idempotence ou isolation doit être documentée avant la campagne.
- **Récupération d'interruption :** une panne pendant reset/rebuild doit laisser READ hors service ou en état de reprise cohérente ; aucune stratégie de restauration séparée d'un ancien checkpoint avec de nouveaux slots/effets n'est acceptable.

### Cas déterministes

| ID | Stimulus / incident | Oracle de réussite |
|---|---|---|
| RCV01-A | READ peuplé de CurrentPot, PotVersion, sous-versions, balances, listes, bindings, résultats et slots ; déclencher reset total | Aucune projection, slot ni checkpoint ancien ne subsiste dans le périmètre réinitialisé ; WRITE est intact |
| RCV01-B | Un Worker READ possède un claim et exécute une transaction au moment de la demande de reset | Barrière empêche un commit tardif après reset ; rollback/achèvement de l'ancien traitement avant réinitialisation, avec absence de réapparition d'effets |
| RCV01-C | Arrêt après reset mais avant relance des Consumers | READ reste explicitement indisponible ; ne sert pas un résultat vide comme une reconstruction achevée |
| RCV01-D | Reconstruire sans nouveaux consumptionIds depuis les cinq séquences événementielles conservées | Checkpoints repartent de zéro puis progressent ; mêmes IDs de Consumption ; projections attendues équivalentes aux valeurs autoritatives |
| RCV01-E | Retraiter un même `POT_EVENT` par les trois Consumptions directes (`MaterializePotVersion`, `MaterializePotBalance`, `MaterializePotVisibility`) après reset | Projections `PotVersion`/`SubobjectVersion`/`CurrentPot`, `PotBalance` et `PotVisibilityState`/`CurrentUserPotList` recréées exactement, sans collision ni état résiduel |
| RCV01-F | Reconstruction avec événements Pot et Identity entrelacés, Consumptions Balance/Visibility désynchronisées | Convergence indépendante vers CurrentPot/PotVersion, Balance, visibilité, CurrentBinding et résultats ; aucune condition d'ordre artificielle |
| RCV01-G | Panne au milieu de la reconstruction, avec quelques checkpoints et slots déjà créés | Reprise sûre à partir des données durables ou nouveau reset total coordonné ; aucune perte par avance incohérente de checkpoint |
| RCV01-H | Simuler restauration partielle : ancien checkpoint + slots effacés, ou projection effacée sans son slot | Procédure refusée/non autorisée ; ne pas déclarer READY un READ dont la preuve de complétion est cassée |
| RCV01-I | Réinitialiser alors que des slots READ libres, claimés et quarantainés coexistent | Tous les slots et checkpoints READ du cycle précédent éliminés ; aucun état antérieur ne bloque le ré-enrôlement complet après reset |
| RCV01-J | Démarrer une Consumption de replay indépendante avec nouvel ID sur événements historiques | Slots et checkpoints nouveaux propres à ce nouvel ID ; aucun réemploi d'ID de Consumption ou collision silencieuse |
| RCV01-K | Rejeu indépendant vers projection déjà alimentée par la Consumption initiale | Coexistence interdite si non démontrée sûre, ou preuve d'idempotence/isolation explicite ; pas de double écriture incohérente |
| RCV01-L | Demandes WRITE encore en arrivée pendant rebuild READ, puis arrêt et drainage mesurés | La borne de reconstruction/READY est définie (préfixes observables et projections requises), et les nouvelles publications sont ensuite consommées sans perte |
| RCV01-M | Requêtes API READ authentifiées pendant et après reset | Indisponibilité pendant reconstruction ; après READY, lectures complètes et autorisations via CurrentBinding, jamais bypass de sécurité |
| RCV01-N | Comparer état reconstruit avec oracle de l'état logique attendu, y compris versions historiques de sous-objets et PotBalance | Concordance des valeurs, versions et invariants ; aucun doublon, trou ou dérive de version |

### Méthode, preuves et décision opérationnelle restant à détailler

JUnit/ PostgreSQL réel avec Workers simultanés, points de pause déterministes, crash/restart contrôlé et vérification de chaque famille de projection. Bruno vérifie indisponibilité READ, remise en service et autorisations. Capturer états avant/après, statut de barrière, ensembles d'IDs, checkpoints, slots, contenu projeté et bornes de publication WRITE. Ne pas présumer que l'arrêt d'un processus tue instantanément ses transactions : la preuve inclut leur résolution physique.

**Point de cadrage pour V3-CADRE :** choisir et décrire la **barrière effective d'arrêt/reprise** et l'oracle de `READY` (notamment en présence de WRITE continu) ; le présent protocole définit ce qui doit être prouvé mais ne prétend pas avoir tranché ces mécanismes opérationnels. L'implémentation ne peut pas décider silencieusement d'une barrière plus faible.

**PASS REC-01** : RCV01-A à RCV01-N, reset sans survivants, impossibilité de commit ancien après reset, rejeu fidèle avec mêmes IDs, replay indépendant isolé et API protégée tant que READ n'est pas déclaré prêt. Aucun test V3 n'est encore exécuté.

**DÉCISION VALIDÉE :** une reconstruction complète de READ est une opération exceptionnelle de maintenance avec **indisponibilité totale temporaire des consultations READ**, acceptée explicitement. La procédure retenue : arrêter effectivement tous les writers et transactions READ, réinitialiser conjointement projections, slots, claims/propriétés et checkpoints READ, réutiliser les mêmes `consumptionId`, puis reconstruire depuis les cinq séquences d'événements WRITE immuables.

**Condition de réouverture :** capturer quatre préfixes sûrs de publications committées `B` dans WRITE ; la disponibilité `READY` n'est déclarée qu'après progression des six Consumptions READ jusqu'aux préfixes `B` et achèvement de **tous leurs slots enrôlés jusqu'à `B`** (`PotVersion`/`SubobjectVersion`/`CurrentPot`, `PotBalance`, `PotVisibilityState`/`CurrentUserPotList`, `CurrentBinding`, `CommandResult` et `IdentityRequestResult`). Le checkpoint de découverte seul ne vaut pas achèvement tant que des slots subsistent. Les événements postérieurs à B ne bloquent pas READY ; READ reprend son contrat habituel d'éventuel retard, `minVersion` et complétude de chaque consultation.

**Garde-fous :** ne jamais confondre perte de propriété de partition avec arrêt des claims déjà acquis : les anciens writers doivent être physiquement arrêtés ou neutralisés avant le reset. Si l'exclusivité de maintenance est perdue ou si le reset est interrompu, ne pas ouvrir READ ; reprendre une reconstruction totale cohérente. Aucun fencing/generation permanent de reconstruction et aucun arrêt prolongé de WRITE ne sont requis **sous condition que cette exclusivité soit effectivement prouvée**.

**Impact REC-01 :** considérer cette décision comme le contrat de test RCV01-A à N ; les cas de writer tardif, slot READ encore non terminé sous `B`, publication au-delà de B et échec de reconstruction sont des tests bloquants pour la remise en service.

**Statut : C-02 et C-03 conceptuellement arbitrés dans SPEC-05 et SPEC-09 ; preuves V3-6/V3-7 non exécutées. Décision de clôture globale séparée.**

## 11. Exploitation

### OPS-01 — Protocole et oracles

**Statut : protocole proposé, non exécuté.** Référence V3-4/V3-5B/C : Discovery et Engine possèdent **deux boucles adaptatives indépendantes**, sans notification obligatoire, avec round-robin entre Consumptions, capacités configurables par Worker et Consumption, lots bornés et sélection de slots sans famine durable. La stabilité ne se déduit ni d'un débit HTTP élevé ni de la seule disparition des slots Command : elle exige progression des événements, des Consumptions READ et des projections READ.

### Oracles et indicateurs

- **Équité sous concurrence :** lorsqu'une Consumption dispose de travaux éligibles et d'une capacité non nulle, une autre Consumption continuellement alimentée ne doit pas la priver indéfiniment de Discovery ou d'acquisition Engine. Les slots différés par `retryAfter` ou claims actifs ne sont pas éligibles avant échéance.
- **Pas de famine interne :** dans une même Consumption, les travaux éligibles plus anciens doivent finalement être sélectionnés malgré le renouvellement permanent de nouveaux slots. Un `LIMIT` sans politique de progression n'est pas, à lui seul, une preuve.
- **Reprise :** un claim expiré peut être repris normalement lorsque la partition est effectivement possédée ; l'ancien détenteur ne peut pas finaliser après remplacement. Les erreurs persistantes restent diagnostiquables sans boucle chaude et sans faux résultat terminal.
- **Polling à vide :** mesurer appels SQL, cycles, réveils, CPU, mémoire, connexions et WAL quand il n'y a aucun travail ; le moteur doit éviter la sursollicitation PostgreSQL. Aucun nombre arbitraire de requêtes/s ou CPU % n'est fixé à ce stade : collecter une base mesurée et détecter les comportements manifestement non bornés.
- **Polling sous charge :** mesurer séparément la latence source→slot (Discovery), slot éligible→acquisition (Engine), temps de travail, taux de retries, collisions et âge du backlog par Consumption. Éviter les scans répétés proportionnels à tout l'historique ou à toutes les tombstones.
- **Capacités :** les plafonds souples de slots ralentissent l'enrôlement mais ne sautent pas de travail ; les limites Worker/Consumption doivent être respectées sans famine. L'autoadaptation reste compatible avec les SLO de V3-5C ; toute optimisation supplémentaire doit être justifiée par mesure.
- **Durabilité du rattrapage :** 10 commandes/s soutenues sur Pots moyens indépendants ne doivent pas produire de backlog à croissance continue ; en pointe 50/s, backlog temporaire accepté sous réserve de résorption ; scénario de 10 000 commandes et leurs matérialisations sous 1 nouvelle commande/s doit se terminer en ≤ 5 minutes, à démontrer en PERF-01.

### Scénarios de preuve

| ID | Mise en situation | Oracle / observation requise |
|---|---|---|
| O01-A | Plusieurs Consumptions constamment alimentées, une Consumption à faible volume | La petite Consumption progresse malgré la grosse ; compter les opportunités Discovery/Engine et latences d'attente |
| O01-B | Une même Consumption reçoit continuellement de nouveaux slots pendant qu'un lot ancien reste éligible | Les slots anciens finissent par être acquis ; aucune famine causée par la sélection |
| O01-C | Mélange de slots libres, claims valides, claims expirés et `retryAfter` futurs | Seuls les slots réellement éligibles sont acquis ; retryAfter respecté ; claims expirés repris sans duplication |
| O01-D | Une partition change de propriétaire sous charge | Le nouveau propriétaire reprend Discovery/acquisition ; claims valides des anciens restent finalisables ; pas de pause durable |
| O01-E | Plafond souple de slots atteint pour une Consumption/partition | Discovery suspend l'enrôlement sans perdre la suite ; reprend à capacité libérée et checkpoints corrects |
| O01-F | Workers saturés et capacités limitées par Consumption | Respect des limites ; les autres Consumptions avec capacité disponible progressent ; aucune saturation permanente du pool de connexions |
| O01-G | Longue période à vide, puis arrivée isolée d'une commande | Coût de polling (SQL/CPU/réveils) mesuré ; latence de réveil et chaîne E2E mesurées, sans hypothèse de notification |
| O01-H | Charge nominale, soutenue et pointe 1/10/50 commandes/s, Pots moyens indépendants | Débit offert/admis/terminal distingué ; backlog et quantiles par étape ; comparaison aux SLO validés, détaillée en PERF-01 |
| O01-I | Erreurs techniques persistantes, puis réparation de la cause | Reprises temporisées sans boucle chaude ni terminalisation fictive ; reprise de progression après réparation |
| O01-J | Nombreux événements hors partition, historique global 10k/1M/10M événements | Mesurer sélectivité, plans SQL, positions inspectées, nombre de lignes lues, progression de checkpoints ; pas de requête démesurée ignorée |
| O01-K | Volume important de slots déjà réclamés/différés et quelques slots disponibles | Les slots éligibles restent trouvables sans lecture déraisonnable des claims inéligibles ; absence de famine |
| O01-L | Déconnexion/crash d'un Worker puis redémarrage et renouvellement de claims | Observabilité des expirations, reprises, slots persistants et absence de double finalisation |
| O01-M | Pause des Workers READ et constitution d'un backlog d'événements/slots READ, tandis que les commandes continuent | Les deux boucles reprennent indépendamment ; progression visible par famille, pas seulement disparition des commandes |
| O01-N | Instrumentation volontairement décalée/omise sur Discovery ou Engine dans un banc de vérification | La validation des métriques signale une frontière erronée ou absente ; simple présence d'une série ne suffit pas |

### Conditions de reproductibilité et interprétation

Combiner **JUnit/PostgreSQL** avec barrières pour les invariants de claims et de progression, **k6** pour les charges offertes, et **Prometheus/mesures PostgreSQL** pour CPU, WAL, sessions, plans, nombre d'exécutions et latences. Isoler autant que possible les injecteurs et la supervision des deux machines de référence. Fixer en V3-5D les nombres de partitions, Workers, tailles de lots, limites de capacité, populations, fenêtres de mesure et stratégie de collecte ; conserver la configuration exacte de chaque campagne.

Les propriétés « aucune famine » et « pas de surcharge catastrophique » exigent des **oracles opératoires** : fenêtre et volume d'essai, borne de progression et seuil de coût par scénario devront être pré-enregistrés après étalonnage, sans transformer une mesure défavorable en PASS rétrospectif. La stabilité exige l'observation simultanée des débits terminalisés et du backlog (nombre et âge) ; une admission à 10/s ne démontre pas que le système les traite à 10/s.

**PASS OPS-01** : O01-A à O01-N, équité et reprise démontrées, sélection utile sous backlog, coût de polling quantifié, métriques fiables et absence de saturation structurelle ; confirmation des SLO chiffrés renvoyée à PERF-01. Aucun test V3 exécuté.

## 12. Performance

### PERF-01 — Protocole et oracles

**Cas discriminants AUD-V3-013 (non exécutés) :** mesurer le traitement à V d'un Grand Pot avec modification d'un simple libellé, modification de poids, modification des Financials, mutation multi-cibles, matérialisation hors ordre et rejeu READ vide. Vérifier que `MaterializePotVersion` reconstitue toutes les références mais ne charge les contenus financiers inchangés que lorsqu'ils manquent en READ ; contrôler idempotence et contenu exact lors des races sur sous-version. Pour `MaterializePotBalance`, vérifier le recalcul complet et exact sans N+1 et sans dépendance à V−1 ; pour HTTP, comparer octets bruts/compressés, exhaustivité/ordre des 2 000 références, réponse sans détails Financials, chargements détaillés par lots et absence de troncature. Relever latences, lignes lues, nombre de requêtes, CPU, mémoire, WAL et stockage historique. Aucun seuil nouveau ni PASS V3 n'est revendiqué.

**Statut : protocole de qualification proposé, non exécuté.** Les nombres de V3-5C sont des **SLO à prouver**, et non des résultats déjà observés. La campagne doit démontrer à la fois une réponse correcte/complète, une latence E2E mesurée et une capacité soutenable. Le seul débit des admissions HTTP ou la seule disparition des slots Command est insuffisant.

### 24.1 Environnements, données et calendrier des campagnes

- **Configuration A — moteur :** PostgreSQL dédié 4 vCPU/8 Go RAM ; runtimes/API/Workers sur machine dédiée 4 vCPU/8 Go RAM ; liaison locale. Générateurs k6 et supervision idéalement isolés. Authentification externe contrôlée, **contrôles d'autorisation Pocoma inchangés**. Fixer versions logicielles, PostgreSQL, mémoire, pools, schéma, nombre de partitions et Workers, quotas, paramètres Discovery/Engine, indices, WAL et journal de configuration avant toute campagne.
- **Configuration B — intégrée :** même base, Keycloak réel sur ressources isolées. Campagnes ciblées OIDC/Registration/Link/Unlink, validation JWT et binding utilisable ; séparer acquisition d'un token OIDC et appels ordinaires avec token valide. Ne pas faire de renouvellement/login à chaque commande, sauf scénario explicite distinct.
- **Pots :** petit 5 shareholders/20 expenses ; moyen 20/200 ; grand 100/2 000. **Volumes historiques distincts :** 100 Pots/10k événements ; 10k Pots/1M événements ; 100k Pots/10M événements. Les chiffres sont des profils de données, sans limite fonctionnelle implicite. Conserver jeu initial/seed, proportions d'opérations et versions. Mesurer historique long sur la base réellement peuplée, sans extrapoler à partir d'une table vide.
- **Charge offerte en boucle ouverte** (`constant-arrival-rate` k6 ou équivalent, capacité suffisante de VUs) : 1/s nominal, 10/s soutenu, 50/s pointe. Enregistrer **itérations prévues, réellement déclenchées, admissions committées, commandes métier appliquées, rejets, erreurs et projections complètes**. Les `dropped_iterations` sont un échec de validité/capacité, pas une façon de réduire artificiellement le dénominateur.
- **Profil mutations :** jeu reproductible couvrant création/modification/suppression, multi-cibles et révisions non financières ; pour chaque SLO de « nouvelle version du Pot », mesurer séparément les **commandes appliquées qui créent V**, sans les diluer avec no-op/rejets. Publier parallèlement les taux et latences de toutes les demandes. Ne pas favoriser artificiellement les commandes sans effet.

### 24.2 SLO et scénarios maîtres

| ID | Campagne | Offre / profil | Objectif contractuel |
|---|---|---|---|
| F01-A | Pot moyen nominal, indépendants | 1 commande/s, Pot 20/200, conf. A | **p95 ≤ 500 ms** admission durable → balances exactes disponibles et **p95 ≤ 500 ms** admission → Pot complet |
| F01-B | Pot moyen soutenu, indépendants | 10 commandes/s, Pot 20/200, conf. A | **p95 ≤ 1 s** admission → Pot complet, **sans croissance continue** du backlog |
| F01-C | Pointe répartie | 50 commandes/s, Pot 20/200, conf. A, fenêtre bornée puis retour nominal | **p95 ≤ 3 s** admission → Pot complet durant la pointe ; backlog temporaire accepté, résorption observée |
| F01-D | Grand Pot nominal | 1 commande/s, Pot 100/2 000, conf. A, Pots indépendants | **p95 ≤ 2 s** admission → Pot complet |
| F01-E | Identity nominale | Registration et Link, chacun avec échantillon distinct, conf. A | **p95 ≤ 500 ms** admission durable → première lecture réellement autorisée grâce au nouveau bindingToken |
| F01-F | Keycloak intégré | Parcours API/OIDC/Identity ciblés, conf. B | Vérifier les contrats sécurité, quantifier l'écart A/B et les coûts OIDC séparément ; **pas de nouveau SLO chiffré B inventé** |
| F01-G | Backlog de rattrapage | 10 000 commandes admises, travail dérivé à compléter ; redémarrer Workers à 1 nouvelle commande/s, conf. A | **≤ 5 min** jusqu'aux 10 000 commandes initiales terminales **et** toutes leurs projections requises prêtes |
| F01-H | Contention même Pot | Commandes concurrentes sur un Pot, conf. A, avec périodes de contention et retour normal | Absence de double/perte/commit partiel, convergence, retries/rejets corrects, backlog résorbé ; **pas de SLO de latence chiffré** |
| F01-I | Profils historiques et coût SQL | trois volumes historiques et plusieurs tailles de Pots | Mesurer amplification des requêtes, lignes lues, CPU/WAL/IO, latences et index/plans ; ne pas présenter de seuil implicite |
| F01-J | Polling inactif puis première commande | moteur au repos puis travail isolé | Coût SQL/CPU/réveils à vide, réactivité Discovery/Engine et E2E ; diagnostiquer les attentes, sans nouvelle mécanique imposée |

### 24.3 Durées et échantillons de qualification proposés

Ces durées sont un **protocole initial à ratifier avant exécution**, non des objectifs métier nouveaux :

| Campagne | Échauffement hors statistiques | Fenêtre évaluée | Volume offert indicatif |
|---|---|---|---|
| A nominal moyen et D grand | 3 min | **20 min chacune** | ~1 200 commandes/campagne |
| B soutenu | 5 min | **15 min** | ~9 000 commandes |
| C pointe | 5 min de base soutenue | **3 min à 50/s**, puis ≥ 10 min retour nominal | ~9 000 commandes offertes durant la pointe |
| E Identity | 3 min | **20 min Registration et 20 min Link séparément** | ~1 200 de chaque opération |
| F intégrée | 3 min | ≥ 10 min pour chaque parcours ciblé | nombre et débit documentés |
| G rattrapage | Base préchargée, Workers stoppés | temps depuis redémarrage jusqu'au dernier jalon | 10 000 commandes historiques + 1/s nouvelles |
| H contention | 3 min | ≥ 10 min de contention, puis drainage | répartition et parallélisme consignés |

Répéter les campagnes comparables **au moins trois fois** avec données et environnement remis dans un état défini, rapporter chaque exécution séparément, et ne pas cacher un FAIL en moyennant plusieurs runs. Les tailles utiles concernent les **commandes effectivement appliquées et observables** : si moins de 300 mesures valides dans une population SLO, la campagne est **inconclusive**, non PASS, même si le p95 affiché paraît favorable. Documenter p50/p95/p99, nombre d'observations, bornes/variabilité et données brutes ; les campagnes de stabilité prolongée peuvent être ajoutées si 15 min ne suffisent pas à distinguer une dérive de backlog.

### 24.4 Définition des mesures et oracle E2E

1. **Départ temporel :** réponse d'admission HTTP confirmant la persistance durable, horodatée par un chrono monotone **côté client** ; enregistrer aussi la durée HTTP séparément. Le point de départ contractuel « admission » ne doit pas être confondu avec le début de l'envoi de la requête. Une commande refusée à l'admission n'appartient pas aux commandes appliquées.
2. **Jalon Command :** retenir `requestId`, résultat terminal, `potId` et `resultingPotVersion=V`. Prouver que la commande a créé une nouvelle V, pas seulement reçu un résultat de succès sans effet.
3. **Balance :** vérifier la disponibilité **exacte** de `PotBalance(P,V)` et son contenu conforme à l'oracle ; une Balance(P,V+1) ne prouve pas que V existe. S'il n'existe pas d'API exposant exactement cette disponibilité, employer un **oracle de test indépendant sur READ** (lecture SQL/sonde de qualification), documenté et non considéré comme une nouvelle API utilisateur.
4. **Pot complet :** observer la première lecture authentifiée autorisée de version W≥V, avec manifeste, Shareholders et balance à W cohérents, sans attendre PotVisibilityState. Pour DeletePot(D), observer en interne CurrentPot→PotVersion(P,D).deleted=true ; HTTP 404 seul insuffisant.
5. **Identity :** après résultat cible `targetVersion`, contrôler CurrentBinding à la version cible ou supérieure, puis **réaliser effectivement une lecture autorisée** avec EUI authentifiée et nouveau token. Utiliser un Pot de référence pré-matérialisé, sans mélanger délai Identity et attente d'un nouveau Pot. Si une version supérieure a immédiatement délié/relié l'EUI, cela ne prouve pas l'utilisation du token cible : isoler les EUI durant ce scénario.
6. **Sondage :** cadence initiale proposée **25 ms** pour les sondes de qualification rapides, avec tolérance et coût mesurés. Le premier succès constitue une **borne supérieure observée**, pas la date exacte du commit. Limiter le nombre de sondes simultanées, isoler leur trafic et publier l'effet sur CPU/SQL ; si le sondage perturbe le système ou est insuffisamment précis face au SLO, campagne inconclusive et protocole à ajuster **avant** certification.
7. **Timeouts/erreurs :** une commande admise dont le jalon n'est pas observé avant la fenêtre de suivi **ne disparaît jamais de l'échantillon**. La classer censurée/timeout et la compter contre le critère de disponibilité ; indiquer clairement que les percentiles des seuls succès ne suffisent pas à certifier le SLO. Rejeter les campagnes dont les pertes de mesures, l'horloge ou les dropped_iterations biaisent le calcul.

### 24.5 Backlog, stabilité, instrumentation et capacité

- **Préparation F01-G :** couper les Workers de traitement/Discovery pertinents tout en maintenant l'admission durable WRITE, puis créer et vérifier exactement 10 000 commandes historiques `PENDING` sur Pots indépendants. Après redémarrage, chacune engendrera les événements et les effets des trois Consumptions READ directes ; on ne suppose **pas** que ces travaux dérivés existent déjà au moment du redémarrage. Photographier le stock avant redémarrage.
- **Fin F01-G :** toutes les demandes initiales TERMINAL avec résultats, Consumptions et projections attendues complétées, sans slot résiduel de traitement réussi (les slots QUARANTINED restent incomplets, jamais des succès).
- **Stabilité B :** séries temporelles de backlog **par Consumption**, âges de la plus ancienne source/slot, débit de complétion et projection E2E ; vérifier qu'aucune pente positive durable ne se cache derrière une moyenne finale. Décrire la régression et la fenêtre choisies avant exécution.
- **Pointe C :** mesurer les latences **pendant** la fenêtre 50/s sans exclure les travaux achevés après son terme ; poursuivre les sondes jusqu'à complétion/timeout et vérifier le drainage après le retour nominal.
- **Instrumentation :** valider avec des injections déterministes que chaque timer/counter mesure la bonne frontière : source→slot, slot éligible→acquisition, préparation, verrou final, commit, événements et branches PotVersion/CurrentPot/Result/Balance/Visibility/CurrentBinding, rollback, retry et expiration. Histogrammes Prometheus exploitables ; aucune étiquette à forte cardinalité ni token dans les logs. Comparer distributions côté client et agrégats internes, **sans additionner les p95 de phases parallèles**.
- **Ressources :** CPU, mémoire, pauses GC, latences SQL, nombre de lignes scannées, plans SQL, connexions/pools, attente/verrous, IO et WAL sur les deux machines ; conserver métriques et plans des phases coûteuses. Distinguer saturation PostgreSQL, moteur, injecteurs et Keycloak.
- **Critères de verdict :** **PASS** seulement si tous les invariants métier/sécurité sont conservés, volume offert réellement tenu, populations et sondes valides, et SLO contractuels satisfaits **sur chaque campagne requise**. **FAIL** si seuil dépassé ou invariant violé ; **INCONCLUSIVE** si échantillons, visibilité des jalons, trafic de sondage ou environnement empêchent d'établir le verdict. Documenter toute limitation de mesure, sans déplacer rétrospectivement les seuils.

### 24.6 Points à ratifier avant V3-CADRE et preuves à produire en V3-7

Les **durées/échantillons**, la cadence de sondage 25 ms, le nombre de répétitions et les modalités exactes de mesure de la visibilité complète sont des **propositions techniques à valider** ; le présent document ne les fait pas passer pour des arbitrages déjà acceptés par le porteur du projet. Fixer aussi la répartition des types de mutation, les paramètres de partition/Workers, les fenêtres de timeout et le contrôle d'identité des sondes.

**Livrables de preuve attendus :** scripts k6 paramétrés, fixtures et seeds SQL/domaine, inventaire de versions/configuration matérielle, export brut des observations, rapports p50/p95/p99, séries backlog et âge par Consumption, traces des commits/erreurs/retries, métrologie PostgreSQL/Prometheus, profils CPU et verdict par scénario. Les scripts historiques k6 de V2 restent une référence d'organisation, jamais une preuve de conformité V3.

**PASS PERF-01** nécessite F01-A à F01-J, tous les SLO chiffrés V3-5C correspondants, stabilité à 10/s, rattrapage en ≤ 5 minutes et qualification distincte de contention, avec métrologie fiable. Aucun benchmark V3 n'est réalisé à ce stade.

**DÉCISION VALIDÉE.** Abandon de l'exigence de prouver, pour les SLO de réactivité utilisateur, que chaque version historique V a été **simultanément** exposable comme Pot complet. À partir d'une commande appliquée retournant `resultingPotVersion=V`, mesurer la **première réponse authentifiée, autorisée et complète de l'API Pot READ pour `minVersion=V`**, servant une version courante `W ≥ V` et tous les composants et balances requis **à W**. Le client ne réclame ni ne reçoit artificiellement `Pot@V` si CurrentPot a avancé. L'horloge E2E démarre à l'admission durable ; conserver les règles de sondage et de traitement des non-succès de PERF-01.

**Séparation des preuves :**
- **Disponibilité utilisateur (READ-01 / PERF-01) :** première lecture réelle avec W≥V, complète et autorisée ; aucun besoin de `PotVisibilityState(P).materializedVersion ≥ V` dans ce jalon **si l'endpoint Pot ne dépend pas de la visibilité**.
- **Correction et progression historique (DOM-01, PRJ-01/02/03, CON-02) :** chaque balance immuable `PotBalance(P,V)` (pour `V < D`) doit exister et être correcte, toutes les Consumptions attendues doivent terminer et la visibilité progresser ; vérifier par oracles internes/états READ et lecture historique autorisée (`V < D`).
- **SLO technique de balance nominale :** conserver séparément le p95 ≤ 500 ms admission→`PotBalance(P,V)` exacte ; contrairement au SLO utilisateur, cette mesure peut reposer sur un oracle technique READ indépendant et précis.
- **Backlog et reconstruction (REC-01 / PERF-01 F01-G) :** garder l'exigence de terminaison des trois Consumptions READ directes et de leurs effets pour chaque événement ciblé ; une lecture courante W≥V ne suffit pas à prouver que tous les slots historiques sont terminés.

**Amendements aux campagnes §24 :** dans F01-A, rapporter deux SLO distincts : première lecture Pot W≥V et Balance(P,V) ; dans F01-B/C/D, le délai Pot désigne uniquement la première lecture READ complète W≥V. Le §24.4 point 4, lorsqu'il suggère la complétude simultanée des projections de V ou une exigence `PotVisibilityState≥V` pour la lecture courante, est **supplanté** par le présent contrat. Aucun nouveau jalon par version historique pour les campagnes de réactivité.

**Effet sur la revue §30 :** **C-03 RÉSOLU AU NIVEAU CONCEPTUEL** par simplification du contrat de mesure. C-01 (consentement IdP) et C-02 (maintenance READ) disposent également de décisions de principe ; il reste la revue documentaire finale, la consolidation du doublon CON-06 et la soumission explicite de V3-CADRE. Aucun code/DDL ni test V3 exécuté.

## 13. Critères de clôture

Certifier toutes les lignes de la matrice avec résultats vérifiables, conserver les protocoles distincts C-01 (consentement externe Link), C-02 (reconstruction READ exclusive), C-03 (jalons de latence), et les familles RCV01-A à N, F01-A à J, A10-A à K, A11-A à N. Aucun scénario fondé uniquement sur une ancienne Projection Task, un retry illimité ou l'absence de quarantaine n'est exigible. Les mesures V3 et tests restent à exécuter en V3-6/V3-7. Aucune implémentation réalisée par le présent changement.

## INV-01 — Certification du parcours d'invitation (non exécutée)

- INV-A : création autorisée au seul créateur ; mutation WRITE + INVITATION_EVENT + complétion atomiques ; zéro POT_EVENT et version Pot inchangée.
- INV-B : rejet sans événement métier ; CommandResult sans lien ; indépendance de matérialisation de CommandResult et CurrentInvitation.
- INV-C : créateur retrouve son lien par creationRequestId dans READ ; EUI tierce et identifiant inconnu indiscernables (404).
- INV-D : invité authentifié avec token valide reçoit seulement les Shareholders actifs libres ; accès au Pot complet, à Expense, aux soldes et aux identifiants internes refusé.
- INV-E : expiration, token falsifié, Pot supprimé, aucun Shareholder libre ou utilisateur déjà rattaché : aucun rattachement proposé.
- INV-F : deux utilisateurs revendiquent le même Shareholder ou un utilisateur deux Shareholders simultanément : au plus un succès transactionnel.
- INV-G : READ retardé affiche une place déjà prise ; WRITE rejette sans mutation partielle.
- INV-H : événements invitation hors ordre, rejeu et reconstruction READ à blanc : état monotone et identique.
- INV-I : aucun secret dans INVITATION_EVENT, logs ou cache partagé ; lien récupérable mais accessible exclusivement à creatorEui.
- INV-J : multi-rattachement général possible, mais invitation refusée dès qu'un premier Shareholder est rattaché ; créateur non-Shareholder distinct.

