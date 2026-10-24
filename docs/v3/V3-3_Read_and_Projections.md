# Pocoma V3-3 — READ, projections et parcours WRITE → READ

**Statut : CONCEPTION CONCEPTUELLE VALIDÉE, sous réserve des mécanismes V3-4, tests V3-5 et revue globale V3-CADRE. Non implémentée.**  
**Date :** 2026-10-22. **Canon :** [V3_Canon_and_Roadmap.md](V3_Canon_and_Roadmap.md).  
**Fondations :** [Pot V3-1](V3-1_Domain_and_Versioning.md), [Identity V3-1](V3-1_Identity_Domain_and_Versioning.md), [événements/consommables V3-2](V3-2_Events_Requests_and_Consumables.md).

## 1. But et périmètre

READ répond aux consultations courantes **sans appeler WRITE**. Les données peuvent être en retard sur WRITE ; elles portent leurs versions, sont exactes à ces versions et ne sont jamais présentées comme plus fraîches qu'elles ne le sont. Le Pot courant est rendu **complet ou temporairement indisponible** : aucune réponse partielle trompeuse.

**Hors périmètre actuel :** consultation d'une version historique arbitraire, `READ_REQUEST`, génération asynchrone de composition `HistoricalPot` et nouvelle famille de consommables afférente. Les versions de sous-objets restent historisées dans READ, mais cela n'engage pas la disponibilité d'une API historique.

Règle de simplicité : pas de snapshots complets Pot par version, pas de chaîne d'indirection pour les balances, pas de `CurrentBalance` mutable, pas d'annuaire inverse Identity, pas de copie de l'état du Pot dans la liste utilisateur.

## 2. Inventaire des projections

| Projection logique | Nature | Clé / contenu minimal |
|---|---|---|
| `CurrentPot` | Mutable monotone | `potId`, `potVersion`, références aux versions locales des Headers/Details/Weight/Financials et objets enfants ; état du Pot incluant suppression |
| `SubobjectVersion` | Immuable | Identité métier de composante + version locale, valeur historique et informations de validité utiles ; toutes les nouvelles versions sont conservées |
| `PotBalance` | Immuable | `(potId,potVersion)`, soldes complets calculés sur `Pot@V` |
| `CurrentUserPotList` | Mutable monotone | `pocomaUserId` → entrées `(potId,potVersion,visible)` de tous les Pots ayant été visibles au moins une fois |
| `CurrentBinding` | Mutable monotone | Identité EUI `(issuer,subject)`, `externalUserIdentityId`, `pocomaUserId?`, `bindingToken?`, `identityVersion` |
| `CommandResult` | Immuable, terminal seul | `requestId`, issue et informations de résultat ; version Pot résultante en cas de succès |
| `IdentityRequestResult` | Immuable, terminal seul | `requestId`, issue et informations de résultat ; pour succès, identifiant de l'EUI **cible** et version EUI résultante |

Les sept entrées sont des **projections logiques**, pas une obligation de sept tables. Les résultats `PENDING` n'existent pas en READ : résultat absent ≠ demande encore pendante (la matérialisation peut être en retard).

## 3. CurrentPot, sous-objets et balances

Chaque événement `POT_EVENT(P,V)` permet la reconstruction complète de l'état autoritatif `Pot@V`, indépendamment de `Pot@(V−1)`. Les versions immuables des composantes sont alimentées au fil de l'eau, y compris quand un événement ancien arrive après un événement plus récent.

`CurrentPot` représente la **plus haute version effectivement matérialisée**, sans garantie qu'elle soit la dernière WRITE. Son remplacement est conditionné atomiquement par `incomingVersion > storedPotVersion`. La matérialisation des sous-objets historiques ne doit **pas** être supprimée lorsque la mise à jour du pointeur courant est écartée pour version inférieure. Les versions immuables contenues dans une même transaction et le pointeur courant sont écrits de manière atomique, sans présumer que les versions référencées issues d'autres événements soient déjà toutes présentes. Lecture complète uniquement si toutes les références sont résolues.

**Balances :** recalcul **intégral** des soldes à **chaque** nouvelle version globale Pot V, y compris mutation de libellé ou autre changement non financier. `PotBalance(P,V)` est immuable ; aucune réutilisation d'une version antérieure, aucun raccourci par indirection. Les calculs à différentes V peuvent terminer hors ordre. La consultation de `CurrentPot(P,V)` ne peut présenter comme complète que `PotBalance(P,V)` et les sous-objets correspondant aux références de ce même V. Le coût assumé doit être qualifié en V3-5.

Une lecture capture une fois `CurrentPot.potVersion=V`, puis résout exclusivement les références et balances applicables à V. Le déplacement concurrent de `CurrentPot` à V+1 n'autorise pas un mélange de versions ; les objets immuables doivent rester accessibles pour les lectures en cours.

## 4. CurrentUserPotList et autorisation

Entrée conceptuelle : `(pocomaUserId, potId, potVersion, visible)`. Une entrée est créée à la **première visibilité métier** et conservée même lorsqu'elle devient invisible. `potVersion` représente la **dernière version à laquelle la visibilité a été évaluée et enregistrée** pour cette entrée, pas forcément la dernière mutation du Pot. Une version inférieure ou égale ne peut plus changer la ligne. Il faut pouvoir rechercher efficacement les entrées actuellement visibles par `potId` et `visible=true` ; les entrées historiques à `false` restent conservées mais ne participent plus aux comparaisons.

Règle de visibilité métier pour un Pot actif : créateur immuable **ou** utilisateur associé à au moins un Shareholder. Pot supprimé : invisible pour tous. Un même utilisateur peut être associé à plusieurs Shareholders ; détacher l'un ne le rend pas invisible si un autre lien subsiste. Une entrée absente ne signifie pas que l'utilisateur peut accéder au Pot. La projection sert la navigation ; l'autorisation de sécurité reste séparée, notamment pour les entitlements externes.

**Matérialisation complète par Pot :** `PotVisibilityTask(P,V,visibleUserIds[])` contient les utilisateurs visibles à V. Une ligne technique `PotVisibilityState(potId, materializedVersion)` indique la dernière version intégralement matérialisée. Dans **une transaction READ unique**, verrouiller cette ligne (initialisation concurrente sûre), ignorer les tâches V inférieures ou égales, lire les entrées actuellement `visible=true`, comparer avec `visibleUserIds`, écrire les entrées visibles et celles devenues invisibles avec V, avancer `materializedVersion`, terminaliser la tâche et supprimer son slot. Les entrées déjà invisibles ne sont pas relues. Le verrou commun évite les insertions fantômes et garantit la correction des versions hors ordre, ce qu'un simple CAS par entrée ne garantit pas lorsque certaines entrées n'existent pas encore.

Consentement : seul l'utilisateur lui-même peut rattacher son `PocomaUser` à un Shareholder ; lui-même ou une autorité Pot adéquate peut le détacher. Le domaine Pot porte `ShareholderDetails.userId` mais l'API de commande ne permet pas de fournir arbitrairement un autre `PocomaUserId`. Identité de l'auteur résolue à l'exécution depuis EUI + bindingToken vérifié. `PocomaUserId` n'a pas besoin d'être exposé publiquement pour les usages présents. Les historiques des rattachements restent immuables.

## 5. CurrentBinding et résultats terminaux

`CurrentBinding` est **l'unique projection Identity retenue**, alimentée depuis les événements de mutation EUI, par lecture de l'état EUI@version complète et écriture directe READ monotone. Les événements de création du `PocomaUser` ne nécessitent aucune projection READ. Le `bindingToken` ne doit être révélé qu'après authentification/autorisation appropriées ; READ peut être en retard après Link/Unlink, **WRITE Identity reste l'autorité de validation à l'exécution**.

`CommandResult` et `IdentityRequestResult` sont copiés **directement** depuis le résultat terminal autoritatif à la consommation des événements de complétion, sans Projection Task. Succès et rejet sont terminaux et immuables. `CommandResult` réussi indique `potId` et `resultingPotVersion` (même pour succès sans effet : version courante au moment de l'exécution). Un succès Identity (Registration, Link, Unlink) indique la **cible EUI**, identifiant stable et `targetVersion` ; Registration crée aussi un User v1, mais aucune cible supplémentaire n'est nécessaire pour suivre `CurrentBinding`. Les échecs portent les motifs adéquats, sans version résultante inventée.

## 6. Lecture « read your writes » asynchrone

Le résultat terminal peut apparaître dans READ avant ou après la projection du domaine concernée. Le client utilise la version cible fournie par son résultat :
- Command réussie → lire `CurrentPot(P)` avec `minVersion=resultingPotVersion`.
- IdentityRequest réussie → lire `CurrentBinding(EUI cible)` avec `minVersion=targetVersion`.

Le READ sert une **version >= minVersion** ou indique l'indisponibilité temporaire ; atteindre une version supérieure signifie que la mutation a été intégrée à la progression de la projection, **pas que son effet est toujours présent** (par exemple Link suivi d'Unlink). Pour une consultation complète du Pot, la version réellement servie V exige aussi toutes ses composantes et `PotBalance(P,V)`. Aucun appel WRITE synchrone depuis les API READ. La version affichée peut rester plus ancienne que WRITE.

## 7. Consumptions et Projection Tasks — responsabilités conceptuelles

Un `POT_EVENT(P,V)` est le consommable déclencheur de **deux Consumptions indépendantes** :
1. `MaterializeCurrentPot` : reconstitue Pot@V, écrit directement **dans une transaction READ** les nouvelles `SubobjectVersion` et la mise à jour conditionnelle de `CurrentPot` ; la conservation des versions anciennes est indépendante de l'avancement du pointeur courant.
2. `PreparePotProjectionTasks` : reconstitue Pot@V de son côté et produit **atomiquement deux tâches durables**, `PotBalanceTask(P,V,balances)` et `PotVisibilityTask(P,V,visibleUserIds[])`. Les deux tâches ont des matérialisations READ indépendantes.

Cette séparation assume **deux reconstructions possibles de Pot@V** pour éviter le couplage entre écriture directe READ et création des Projection Tasks. Le nombre de tâches est constant (deux) par événement Pot, indépendamment du nombre d'Expenses et d'utilisateurs ; leur **taille**, elle, dépend des données nécessaires.

| Source | Transformation conceptuelle | Destination |
|---|---|---|
| `POT_EVENT` | MaterializeCurrentPot | Direct READ : CurrentPot + sous-objets |
| `POT_EVENT` | PreparePotProjectionTasks | Deux tâches durables : balances et visibilité |
| `PotBalanceTask` | MaterializePotBalance | Direct READ : PotBalance immuable |
| `PotVisibilityTask` | MaterializePotVisibility | Direct READ : CurrentUserPotList monotone |
| `IDENTITY_EVENT` EUI | MaterializeCurrentBinding | Direct READ : CurrentBinding |
| `IDENTITY_EVENT` PocomaUser | Aucune projection requise | Aucun effet READ, consommation néanmoins terminable |
| `COMMAND_COMPLETION_EVENT` | MaterializeCommandResult | Direct READ : CommandResult immuable |
| `IDENTITY_COMPLETION_EVENT` | MaterializeIdentityResult | Direct READ : IdentityRequestResult immuable |

Les noms sont descriptifs, pas des classes ou des identifiants UUID imposés. Les frontières physiques entre WRITE, tâches et READ, atomicités locales, idempotence, fencing, commit incertain, compatibilité des transactions et absence de perte à travers les écritures sont **à définir et démontrer en V3-4**. Ne pas inférer qu'une mutation WRITE et une écriture READ distante sont dans une même transaction.

## 8. Risques, exigences V3-4/5 et clôture conceptuelle

- **Discovery/Consumption :** progression indépendante des quatre familles d'événements V3-2 ; reprise sûre pour les écritures READ directes et les tâches durables ; événements hors ordre, répétitions et commits inconnus.
- **Concurrence :** CAS/monotonie sur CurrentPot/CurrentBinding/visibilité ; protection transactionnelle du différentiel sur les seules entrées visibles par `PotVisibilityState` ; conservation des composants immuables même après perte de la course CurrentPot.
- **Fraîcheur et correction :** API « complet ou temporairement indisponible », `minVersion` et séparations entre absence, retard de matérialisation et accès refusé.
- **Sécurité :** bindingToken READ protégé ; autorisation WRITE Identity ; accès aux Pots décidé hors liste de navigation ; preuve de contrôle EUI cible lors d'un Link reste ouverte.
- **Performance :** reconstructions complètes indépendantes (deux possibles par événement), balances recalculées à chaque V, lectures historiques WRITE groupées/indexées sans N+1, contention des écritures visibilité, taille des tâches, rétention des versions immuables et cohérence des plans PostgreSQL. Mesures V3-5 obligatoires, optimisations seulement si justifiées.
- **Cohérence V3-1/V3-2 :** deux domaines transactionnellement disjoints ; requestId dans les événements de mutation, deux séquences par domaine ; pas de dépendance d'ordre entre événements de mutation, complétions et projections READ.

**La conception V3-3 est validée conceptuellement, non implémentée et non prouvée par tests V3. V3-CADRE reste ouvert.**


## Amendement transversal V3-4 — 2026-10-23

- **Autorisation READ :** `CurrentUserPotList` sert uniquement à la navigation, peut être en retard et n'autorise rien. Le client authentifié présente un `bindingToken`, qui ne suffit jamais à lui seul à authentifier son porteur. READ résout un binding actif dans `CurrentBinding` vers le `pocomaUserId` correspondant et vérifie qu'un Shareholder de `Pot@V` est lié à ce User dans **cette version historique**. Une suppression du Shareholder dans une version ultérieure ne retire pas rétroactivement l'accès à V ; la disparition du binding de `CurrentBinding` retire cet accès. Une fenêtre de propagation après révocation WRITE est acceptée explicitement.
- **Cohérence :** les projections avancent indépendamment. Toute lecture vérifie uniquement les projections nécessaires à sa réponse, aux versions demandées. La liste de Pots d'un User ne promet pas de photographie globale simultanée.
- **Frontières :** les Projection Tasks, leurs slots et leurs effets sont dans READ ; préparation des deux tâches et suppression du slot de préparation sont atomiques en READ. Les journaux d'événements restent en WRITE.
- **Reconstruction :** arrêt des writers READ, réinitialisation cohérente de ses projections, tâches, slots et checkpoints, puis reconstruction à blanc possible avec les mêmes `consumptionId`. Un rejeu **indépendant** crée une nouvelle Consumption et un nouvel ID.

## 9. Amendement V3-5E validé — PotVersion immuable et CurrentPot pointeur (2026-10-23)

**Cet amendement remplace les descriptions antérieures de `CurrentPot` comme conteneur mutable de toutes les références, ainsi que la règle « pas de snapshot de composition Pot par version » en §1.** La projection de composition est **légère** : elle conserve les références, non une duplication des contenus.

- **`PotVersion(potId,V)`**, immuable et matérialisée à chaque version globale du Pot, porte la composition exacte : références de versions locales des composants Pot, Shareholders et Expenses, inclusions/suppressions, et ensemble interne des `pocomaUserId` autorisés par rattachement à un Shareholder **à V**. Elle ne copie ni les sous-objets complets ni les balances. Les données internes d'autorisation ne sont pas exposées dans la réponse client. Cette addition porte l'inventaire logique de sept à **huit projections**.
- **`CurrentPot(potId)`** n'est plus qu'un pointeur mutable monotone vers le `V` maximal matérialisé. La lecture principale résout `PotVersion(P,V)`, les sous-objets des Shareholders et `PotBalance(P,V)` ; elle retourne aussi la liste ordonnée des références versionnées des Expenses, sans charger tous leurs détails. Si une dépendance de V manque, réponse temporairement indisponible, pas de mélange de versions.
- **`MaterializeCurrentPot` inchangée comme Consumption** : reconstruction directe depuis l'événement `POT_EVENT(P,V)` et l'état WRITE@V, puis insertion de `PotVersion(P,V)`, insertion des `SubobjectVersion` nouvelles et mise à jour conditionnelle `CurrentPot` dans **une transaction READ** avec complétion du slot. Une arrivée tardive de V inférieure conserve ses données immuables sans reculer CurrentPot.
- **Sécurité :** chaque requête résout d'abord l'EUI authentifiée et le binding actif dans `CurrentBinding`, puis recherche le `pocomaUserId` autorisé **à V** dans `PotVersion(P,V)` ; l'autorisation historique n'est jamais une capacité permanente. Une liste de navigation visible (incluant éventuellement un créateur non-Shareholder) **n'accorde aucun droit**. Les lectures groupées de références vérifient également l'appartenance exacte au manifeste de la version consultée. Les fenêtres de retard de révocation `CurrentBinding` restent celles de SEC-01.
- **Consultation de version :** ouvrir le courant fige V côté client ; l'UI ne bascule pas silencieusement vers V+1. Les lectures complémentaires paginées prennent explicitement V et des groupes bornés de références. La conservation des `PotVersion` permet ce contrat sans manifeste historique reconstruit ni cache serveur ; une politique d'archivage ultérieure aura besoin d'un contrat de disponibilité distinct.
- **Tâches :** `PreparePotProjectionTasks` produit toujours exactement Balance et Visibility. `PotBalance(P,V)` complète demeure une projection immuable séparée ; visibilité/navigation restent indépendantes et leurs retards ne sont pas une condition de la lecture Pot si l'autorisation et les références sont disponibles.
- **Compatibilité documentaire :** les formulations contraires des §§1–7 et les anciennes preuves PRJ-01/READ-01/SEC-01 sont supplantées par le présent amendement et doivent être réécrites dans les suites de réalisation. Aucune nouvelle Consumption ni séquence événementielle ajoutée.

**Coût reconnu :** O(nombre de références × nombre de versions) en stockage de manifests, avant compressions ; aucune factorisation, worker de compactage ni archivage automatique au premier socle. Tester taille, WAL, CPU et nombre de requêtes. **Architecture décidée conceptuellement, non implémentée.**

## 10. Alignement des contrats Supra V3-5E clôturés (2026-10-23)

**Complément normatif à §9 :**
- `CurrentUserPotList` se consulte par pages **keyset ordonnées `potId` croissant**, avec curseur opaque et aucun snapshot inter-pages. Chaque page est résolue pour l'utilisateur actuellement authentifié ; l'apparition/disparition d'un Pot peut changer les pages futures. Cette liste ne confère jamais d'autorisation de lecture du Pot.
- La lecture principale de `PotVersion(P,V)` donne en une seule réponse **tous les Shareholders et balances exactes à V**, plus le **manifeste complet ordonné des références versionnées d'Expenses** ; aucun découpage de ce manifeste en V3, aucune troncature silencieuse. Les détails d'Expenses sont récupérés via des lots de références de taille bornée, autorisés un par un à V.
- L'accès à la version historique reste fondé sur la présence à V d'un Shareholder rattaché au `pocomaUserId` résolu par un `CurrentBinding` **actif aujourd'hui**. La perte ultérieure de qualité de Shareholder ne retire pas rétroactivement l'accès à cette version ; la disparition du binding actif le retire. Cette règle SEC-01 est explicite et différente de l'usage de la liste de navigation.
- L'idempotence des admissions (Command/IdentityRequest) relève des Supras et de leur persistance atomique en WRITE, **sans nouvelle projection READ**.

Pour éviter toute ambiguïté, ces règles et §9 prévalent sur les formulations antérieures des §§1–8 qui décrivent un autre `CurrentPot` ou l'absence de manifeste historique. Voir V3-5E §§18–20 ; tests V3-5D §§47–49.

## 11. Amendement post-audit — droit READ du créateur (2026-10-24)

**Décision :** l'ensemble interne `authorizedUserIds` de `PotVersion(P,V)` inclut `PotDetails.creatorId` **ainsi que** les `PocomaUserId` associés aux Shareholders présents à V. Ainsi `CreatePot` à V1 est lisible par son créateur sans Shareholder. L'autorisation demeure distincte de `CurrentUserPotList`, qui n'est jamais une source d'autorité ; les droits historiques et le contrôle du `CurrentBinding` actif restent requis. Les formulations antérieures excluant explicitement le créateur non-Shareholder sont **supplantées**.

La suppression d'un Shareholder est une action WRITE réservée au créateur et interdite tant qu'une Expense active le référence. La matrice d'autorisation WRITE est dans V3-1 §9. Adapter SEC-01, PRJ-01 et la preuve V1 créateur ; les réponses 404 restent indistinguables pour un tiers.

## 12. AUD-V3-009 — contrat Supra de lecture CurrentBinding (2026-10-24)

La lecture `GET /identity/me/binding` (nom d'endpoint indicatif) résout exclusivement l'EUI authentifiée. **Aucun bindingToken entrant requis.** Elle restitue seulement `(euiVersion, active, bindingToken si actif)` de cette EUI, sans `pocomaUserId` ni aucun Binding tiers. Le token est une donnée confidentielle, non mise en cache partagé et non journalisée.

`MaterializeCurrentBinding` consomme `IDENTITY_EVENT(EUI,V)` et récupère le Binding autoritatif **à V** en WRITE, puisque le token ne circule pas dans l'événement. Mise à jour monotone de la projection. Après Registration l'EUI enregistrée lit son propre Binding ; après Link le résultat est accessible selon ses droits à E1, mais **E2 doit s'authentifier sous sa propre EUI** pour lire le nouveau Binding E2. Le paramètre `minVersion` doit être évalué à l'encontre de la version de cette même EUI, non celle de l'initiateur.

Cet appel de lecture est une exception **au besoin de posséder préalablement un bindingToken**, et non à l'authentification. Les Commands et IdentityRequests autres que Registration exigent toujours le Binding explicite. Cette section supplante toute ambiguïté antérieure sur la délivrance du token.

## 13. AUD-V3-005 — trois Consumptions directes POT_EVENT (2026-10-24)

**Architecture validée :** supprimer `PreparePotProjectionTasks`, `PotBalanceTask`, `PotVisibilityTask` et leurs effets/slots propres. Les trois Consumptions non-burnables consomment **directement et indépendamment** la même séquence `POT_EVENT(P,V)` :
1. `MaterializePotVersion` (ancien nom `MaterializeCurrentPot`) : charger WRITE@V, insérer les nouvelles `SubobjectVersion` et `PotVersion(P,V)` immuables, avancer monotoniquement `CurrentPot(P)` dans une transaction READ.
2. `MaterializePotBalance` : charger les informations financières nécessaires de WRITE@V, calculer et insérer `PotBalance(P,V)` immuable, sans attente de la Consumption précédente.
3. `MaterializePotVisibility` : charger depuis WRITE@V le créateur et les rattachements Shareholders utiles ; obtenir l'ensemble complet des utilisateurs visibles à V, sans devoir charger les Expenses. Sous verrou `PotVisibilityState(P)` et dans une transaction READ, appliquer le différentiel aux entrées `CurrentUserPotList` et avancer seulement si V est supérieure à la version de visibilité matérialisée.

**Indépendance :** chaque Consumption conserve sa propre Discovery, checkpoint, slots, transaction d'effets, retry et possibilité de progrès, dans le même modèle de Worker. Aucune Discovery commune ni service physique supplémentaire exigés. La lecture complète Pot à V vérifie la disponibilité de PotVersion, Shareholder SubobjectVersion et PotBalance à V ; la visibilité reste indépendante. Les traitements de V peuvent arriver hors ordre ; pas de recul de pointeur ni de visibilité et toutes les balances historiques doivent être conservées.

**Ce §13 supplante explicitement les mécanismes antérieurs de tâches préparées en §§4–9 et dans V3-2/V3-4.** Le nombre de projections logiques READ n'est pas modifié par la suppression des familles de tâches : `PotVersion`, `CurrentPot`, `SubobjectVersion`, `PotBalance`, `PotVisibilityState/CurrentUserPotList`, `CurrentBinding` et résultats restent selon leur inventaire normatif. Aucune implémentation réalisée.

## 14. AUD-V3-008 — consultation Identity et récapitulatif Link (2026-10-24)

**Aucun parcours V3 retenu n'exige de rechercher les EUI par `pocomaUserId`.** Registration, Link, Unlink, Commands, consultation de son propre Binding et matérialisation partent d'une EUI déterminée. La mention « pas d'annuaire inverse Identity » signifie toujours : aucune relation inverse métier, projection inverse ou index inverse ajouté par exigence fonctionnelle. Un index PostgreSQL secondaire reste en général permis si une mesure le justifie ultérieurement, mais **aucun index `CurrentBinding(pocomaUserId)` n'est requis par AUD-V3-008**.

Pour le récapitulatif Link, E1 est l'EUI initiatrice authentifiée ; son Binding actif et son token déclaré sont vérifiés selon les frontières admission/WRITE pertinentes. Le User correspondant permet de compter les entrées `visible=true` dans `CurrentUserPotList` (Pots accessibles au sens de la navigation, non preuve d'autorisation), avec la date de consultation READ et sans garantie de fraîcheur. Le nombre d'EUI liées est **supprimé** du récapitulatif et des obligations de projection/consultation. Aucun nouvel index, projection, annuaire inverse ou lecture WRITE synchronisée n'est introduit. Voir Identity AUD-V3-008 et V3-5D §59.
