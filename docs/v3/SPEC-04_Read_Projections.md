# Pocoma SPEC-04 — READ et projections

**Statut : contrat conceptuel consolidé AUD-V3-001/005/008/009/010/011 ; non implémenté, non certifié en V3.**  
**Consolidation :** 2026-10-24. [Canon](V3_Canon_and_Roadmap.md).

## 1. Principes READ

READ répond aux consultations courantes et historiques à partir de ses seules projections, jamais par une consultation synchrone de WRITE. Les projections peuvent être en retard : toute réponse porte la version effectivement servie, exacte et cohérente. La lecture Pot autorisée est complète à V ou temporairement indisponible, sans mélange de versions. Les Consumptions sont indépendantes, et la navigation n'est jamais une autorité de sécurité. Aucune nouvelle famille READ_REQUEST ni annuaire inverse Identity.

## 2. Inventaire des neuf projections logiques

1. `PotVersion(P,V)` : composition immuable par version, références complètes de sous-objets, manifeste d'Expenses et droits internes à V.
2. `CurrentPot(P)` : pointeur monotone vers la plus haute version PotVersion matérialisée.
3. `SubobjectVersion` : composants métier versionnés et immuables.
4. `PotBalance(P,V)` : soldes rationnels complets et immuables par version active.
5. `CurrentUserPotList` (avec garde `PotVisibilityState`) : navigation mutable, versionnée et monotone.
6. `CurrentBinding(EUI)` : rattachement courant de l'EUI et token réservé à celle-ci.
7. `CommandResult` : résultat terminal immuable et provenance requesterEui.
8. `IdentityRequestResult` : résultat terminal immuable et provenance requesterEui.
9. `CurrentInvitation` : invitation projetée et lien restituable au seul créateur, avec index d'empreinte pour le parcours invité.

Inventaire **logique**, sans obligation de neuf tables. `PotVisibilityState` est une garde technique de la projection de navigation et non une projection métier supplémentaire ; aucune projection Identity inverse.

## 3. PotVersion, CurrentPot et sous-objets

 La projection de composition est **légère** : elle conserve les références, non une duplication des contenus.

- **`PotVersion(potId,V)`**, immuable et matérialisée à chaque version globale du Pot, porte la composition exacte : références de versions locales des composants Pot, Shareholders (avec l'indicateur booléen public `isFree` indiquant si `userId == null`, sans jamais exposer `userId`) et Expenses, inclusions/suppressions, et ensemble interne des `pocomaUserId` autorisés par rattachement à un Shareholder ou comme créateur **à V**. Elle ne copie ni les sous-objets complets ni les balances. Les données internes d'autorisation ne sont pas exposées dans la réponse client. L'indicateur `isFree` ne constitue pas une projection supplémentaire.
- **`CurrentPot(potId)`** n'est plus qu'un pointeur mutable monotone vers le `V` maximal matérialisé. La lecture principale résout `PotVersion(P,V)`, les sous-objets des Shareholders et `PotBalance(P,V)` ; elle retourne aussi la liste ordonnée des références versionnées des Expenses, sans charger tous leurs détails. Si une dépendance de V manque, réponse temporairement indisponible, pas de mélange de versions.
- **`MaterializePotVersion` est la Consumption** : reconstruction directe depuis l'événement `POT_EVENT(P,V)` et l'état WRITE@V, puis insertion de `PotVersion(P,V)`, insertion des `SubobjectVersion` nouvelles et mise à jour conditionnelle `CurrentPot` dans **une transaction READ** avec complétion du slot. Une arrivée tardive de V inférieure conserve ses données immuables sans reculer CurrentPot.
- **Sécurité :** chaque requête résout d'abord l'EUI authentifiée et le binding actif dans `CurrentBinding`, puis recherche le `pocomaUserId` autorisé **à V** dans `PotVersion(P,V)` ; l'autorisation historique n'est jamais une capacité permanente. Une liste de navigation visible (incluant éventuellement un créateur non-Shareholder) **n'accorde aucun droit**. Les lectures groupées de références vérifient également l'appartenance exacte au manifeste de la version consultée. Les fenêtres de retard de révocation `CurrentBinding` restent celles de SEC-01.
- **Consultation de version :** ouvrir le courant fige V côté client ; l'UI ne bascule pas silencieusement vers V+1. Les lectures complémentaires paginées prennent explicitement V et des groupes bornés de références. La conservation des `PotVersion` permet ce contrat sans manifeste historique reconstruit ni cache serveur ; une politique d'archivage ultérieure aura besoin d'un contrat de disponibilité distinct.


L'ensemble interne `authorizedUserIds` de PotVersion inclut le créateur immuable même sans Shareholder et tous les utilisateurs rattachés aux Shareholders à V. Il n'est pas exposé au client. Les versions immuables d'un événement tardif sont conservées sans recul du pointeur CurrentPot.

### Chargement sélectif des sous-versions — AUD-V3-013

Pour chaque `POT_EVENT(P,V)`, `MaterializePotVersion` reconstruit **depuis WRITE@V la composition complète** (composantes, sous-versions locales, suppressions et utilisateurs autorisés à V) ; il ne déduit jamais la composition de `PotVersion(P,V−1)` ni de l'état courant. La reconstruction des références ne nécessite pas de charger systématiquement les contenus détaillés des `ExpenseFinancials` et de leurs participations.

Il vérifie **par lots** les clés immuables de `SubobjectVersion` déjà présentes dans READ, charge depuis WRITE les contenus **historiques exacts** des seules clés manquantes et matérialise ces sous-versions avec `PotVersion(P,V)` et l'avance conditionnelle de `CurrentPot` dans la transaction READ du slot. L'absence d'une sous-version en READ déclenche son chargement ; elle n'exige jamais la complétion préalable d'une autre PotVersion. Les traitements hors ordre, le rejeu à partir de READ vide et les mutations multi-cibles restent pris en charge. Une insertion concurrente de même clé est idempotente seulement si le contenu est identique ; toute divergence est une anomalie technique, jamais silencieusement acceptée. Aucune projection, tâche ou dépendance inter-Consumption supplémentaire.

La composition `PotVersion(P,V)` demeure **complète, immuable et autonome** à chaque V : ni delta, ni pointeur sur une composition antérieure, ni déduplication structurelle exigeant une reconstruction en chaîne. Le coût de stockage historique des références est assumé et à mesurer physiquement (SPEC-09/10).

## 4. Balances

Le calcul des soldes est **complet à chaque version active V**, y compris une mutation non financière, à partir des composantes WRITE@V et sans indirection vers une balance ancienne. Chaque PotBalance(P,V) est immuable ; les différentes versions peuvent être calculées dans n'importe quel ordre. À la version terminale D, la Consumption Balance termine normalement **sans** PotBalance(P,D). Les soldes de toutes les versions antérieures doivent être conservés, même si elles sont matérialisées après D. Voir §10.

Le recalcul de `PotBalance(P,V)` reste intégral et indépendant pour chaque V active, même sans mutation financière, par lecture **groupée** des Expenses actives, Financials, participations et poids applicables à V depuis WRITE. Éviter les accès N+1 ; aucun cache durable, calcul différentiel ou chaînage avec d'autres balances. Les 200 000 participations potentielles d'un Grand Pot constituent une cardinalité de données à traiter, **pas** un nombre prescrit de requêtes SQL. Ce choix ne change pas le traitement terminal D.

## 5. Visibilité et navigation

Entrée conceptuelle : `(pocomaUserId, potId, potVersion, visible)`. Une entrée est créée à la **première visibilité métier** et conservée même lorsqu'elle devient invisible. `potVersion` représente la **dernière version à laquelle la visibilité a été évaluée et enregistrée** pour cette entrée, pas forcément la dernière mutation du Pot. Une version inférieure ou égale ne peut plus changer la ligne. Il faut pouvoir rechercher efficacement les entrées actuellement visibles par `potId` et `visible=true` ; les entrées historiques à `false` restent conservées mais ne participent plus aux comparaisons.

Règle de visibilité métier pour un Pot actif : créateur immuable **ou** utilisateur associé à au moins un Shareholder. Pot supprimé : invisible pour tous. Un même utilisateur peut être associé à plusieurs Shareholders ; détacher l'un ne le rend pas invisible si un autre lien subsiste. Une entrée absente ne signifie pas que l'utilisateur peut accéder au Pot. La projection sert la navigation ; l'autorisation de sécurité reste séparée, notamment pour les entitlements externes.



`MaterializePotVisibility` calcule directement depuis WRITE@V la liste complète des utilisateurs visibles (créateur et rattachements Shareholders pour un Pot actif ; ensemble vide pour D). Sous verrou collectif `PotVisibilityState(P)`, elle compare uniquement les entrées actuellement visibles, écrit les entrées nouvelles et devenues invisibles et avance la version de visibilité seulement si V est supérieure à celle déjà matérialisée. Effets et suppression de slot sont atomiques ; aucun `PotVisibilityTask`. Voir [SPEC-05](SPEC-05_Consumption_Discovery.md).

## 6. CurrentBinding et Identity

La lecture `GET /identity/me/binding` (nom d'endpoint indicatif) résout exclusivement l'EUI authentifiée. **Aucun bindingToken entrant requis.** Elle restitue seulement `(euiVersion, active, bindingToken si actif)` de cette EUI, sans `pocomaUserId` ni aucun Binding tiers. Le token est une donnée confidentielle, non mise en cache partagé et non journalisée.

`MaterializeCurrentBinding` consomme les événements `IDENTITY_EVENT(EUI,V)` de type `EXTERNAL_USER_IDENTITY` (en ignorant les événements `POCOMA_USER`) et récupère le Binding autoritatif **à V** en WRITE, puisque le token ne circule pas dans l'événement. Mise à jour monotone de la projection. Après Registration l'EUI enregistrée lit son propre Binding ; après Link le résultat est accessible selon ses droits à E1, mais **E2 doit s'authentifier sous sa propre EUI** pour lire le nouveau Binding E2. Le paramètre `minVersion` doit être évalué à l'encontre de la version de cette même EUI, non celle de l'initiateur.

Cet appel de lecture est une exception **au besoin de posséder préalablement un bindingToken**, et non à l'authentification. Les Commands et IdentityRequests autres que Registration exigent toujours le Binding explicite. Cette section supplante toute ambiguïté antérieure sur la délivrance du token.
**Aucun parcours V3 retenu n'exige de rechercher les EUI par `pocomaUserId`.** Registration, Link, Unlink, Commands, consultation de son propre Binding et matérialisation partent d'une EUI déterminée. La mention « pas d'annuaire inverse Identity » signifie toujours : aucune relation inverse métier, projection inverse ou index inverse ajouté par exigence fonctionnelle. Un index PostgreSQL secondaire reste en général permis si une mesure le justifie ultérieurement, mais **aucun index `CurrentBinding(pocomaUserId)` n'est requis par AUD-V3-008**.

Pour le récapitulatif Link, E1 est l'EUI initiatrice authentifiée ; son Binding actif et son token déclaré sont vérifiés selon les frontières admission/WRITE pertinentes. Le User correspondant permet de compter les entrées `visible=true` dans `CurrentUserPotList` (Pots accessibles au sens de la navigation, non preuve d'autorisation), avec la date de consultation READ et sans garantie de fraîcheur. Le nombre d'EUI liées est **supprimé** du récapitulatif et des obligations de projection/consultation. Aucun nouvel index, projection, annuaire inverse ou lecture WRITE synchronisée n'est introduit. Voir Identity AUD-V3-008 et SPEC-10 — ID-02.

## 7. Résultats terminaux

**NORMATIF ; complète les projections logiques de §2 et §5.** `CommandResult` et `IdentityRequestResult` contiennent chacun, outre les champs déjà décrits, `requesterEui` (EUI initiatrice authentifiée à l'admission ; référence conceptuelle stable `issuer,subject`). Cette provenance est capturée dans la demande WRITE, jamais déduite d'un Binding courant ni du PocomaUser à l'exécution. Elle existe aussi pour Registration et toute demande admise puis rejetée, y compris pour token invalide.

La Consumption de l'événement de complétion lit en WRITE **la demande ET le résultat terminal** via le `requestId` et matérialise atomiquement en READ le résultat, avec `requesterEui`. L'événement de complétion reste minimal, sans duplication de ces champs. À la consultation, la Supra READ vérifie uniquement l'authentification externe de l'EUI appelante et l'égalité avec `requesterEui` de la ligne READ ; aucune consultation WRITE ni vérification de Binding/bindingToken actuel n'est requise pour son propre résultat. Un Unlink ou un re-Link, même vers un autre PocomaUser, ne transfère pas la propriété des résultats passés. `requestId` seul n'est jamais une autorisation.

Les projections de résultats restent terminales, immuables, sans `PENDING`, sans `EXPIRED` et **sans expiration fonctionnelle dans le modèle V3 actuel**. Pas de mécanisme de purge automatique introduit par cet arbitrage ; toute politique opérationnelle future de conservation exige un contrat explicite compatible avec l'idempotence et les possibilités de reconstruction. Résultat absent et résultat d'un tiers sont indiscernables en HTTP (404), conformément à V3-5E. Aucun nouvel objet/projection. Scénarios SPEC-10 — A10-A à A10-K.

Les résultats peuvent être visibles avant la projection métier. Une Command réussie fournit `resultingPotVersion` (égal à la nouvelle version globale créée, ou à la version courante inchangée `V_courant` pour une commande valide sans effet), réutilisable comme `minVersion` pour les consultations READ ultérieures ; une IdentityRequest réussie indique l'EUI cible et `targetVersion`. Le résultat n'affirme pas que les projections de domaine sont déjà disponibles.

## 8. Consumptions des événements

**Architecture validée :** supprimer `PreparePotProjectionTasks`, `PotBalanceTask`, `PotVisibilityTask` et leurs effets/slots propres. Les trois Consumptions non-burnables consomment **directement et indépendamment** la même séquence `POT_EVENT(P,V)` :
1. `MaterializePotVersion` (ancien nom `MaterializeCurrentPot`) : charger WRITE@V, insérer les nouvelles `SubobjectVersion` et `PotVersion(P,V)` immuables, avancer monotoniquement `CurrentPot(P)` dans une transaction READ.
2. `MaterializePotBalance` : charger les informations financières nécessaires de WRITE@V, calculer et insérer `PotBalance(P,V)` immuable, sans attente de la Consumption précédente.
3. `MaterializePotVisibility` : charger depuis WRITE@V le créateur et les rattachements Shareholders utiles ; obtenir l'ensemble complet des utilisateurs visibles à V, sans devoir charger les Expenses. Sous verrou `PotVisibilityState(P)` et dans une transaction READ, appliquer le différentiel aux entrées `CurrentUserPotList` et avancer seulement si V est supérieure à la version de visibilité matérialisée.

**Indépendance :** chaque Consumption conserve sa propre Discovery, checkpoint, slots, transaction d'effets, retry et possibilité de progrès, dans le même modèle de Worker. Aucune Discovery commune ni service physique supplémentaire exigés. La lecture complète Pot à V vérifie la disponibilité de PotVersion, Shareholder SubobjectVersion et PotBalance à V ; la visibilité reste indépendante. Les traitements de V peuvent arriver hors ordre ; pas de recul de pointeur ni de visibilité et toutes les balances historiques doivent être conservées.

**La présente section sur les Consumptions directes supplante les anciens mécanismes de tâches préparées dans les documents historiques.** La suppression des familles de tâches ne modifie pas l'inventaire des projections logiques READ : `PotVersion`, `CurrentPot`, `SubobjectVersion`, `PotBalance`, `PotVisibilityState/CurrentUserPotList`, `CurrentBinding` et résultats restent selon leur inventaire normatif. Aucune implémentation réalisée.

## 9. Cohérence de lecture et complétude

La lecture principale fige une version V, résout PotVersion(P,V), les Shareholders complets et PotBalance(P,V), puis fournit le manifeste **complet et ordonné** des références versionnées d'Expenses sans charger leurs détails. Ceux-ci sont obtenus par appels groupés bornés à V et autorisés individuellement sur la base du manifeste. La cohérence n'exige pas `PotVisibilityState.materializedVersion>=V`, qui ne conditionne pas la lecture du Pot.

Pour une lecture courante, `minVersion` demande une réponse de version W≥V, sans garantir la persistance de l'effet d'une commande ultérieurement contredite ; un retard de projection autorisée ou de dépendances provoque l'indisponibilité temporaire. La lecture historique explicite à V<D reste permise selon SEC-01. L'autorisation et le choix des réponses HTTP relèvent exclusivement de [SPEC-06 provisoire](SPEC-06_Supra_Contracts.md). `CurrentUserPotList` est paginée par keyset potId croissant, sans snapshot inter-pages.

## 10. DeletePot et version terminale

**Décision normative :** pour `DeletePot(P)` appliqué à la version terminale D, `MaterializePotVersion` insère `PotVersion(P,D)` avec Header `deleted=true` et avance monotoniquement `CurrentPot(P)` vers D. `MaterializePotVisibility` projette l'ensemble visible vide et retire le Pot de la navigation selon son protocole indépendant. `MaterializePotBalance` traite le même `POT_EVENT(P,D)` et **termine avec succès sans calculer ni insérer `PotBalance(P,D)`**. L'absence est intentionnelle et ne signifie ni incomplet ni échec technique. Toutes les balances des versions V<D doivent être calculées et conservées, même si leurs événements sont exécutés après D : chaque Consumption décide à partir de WRITE@V, pas de l'état actuellement supprimé ou de l'avancement des autres Consumptions. Aucun nouveau slot, statut, projection ni dépendance inter-Consumption.

La lecture courante après matérialisation de D retourne `404` ; une lecture historique explicite de D retourne aussi `404`. Une lecture historique de V<D reste possible selon les droits **à V** (créateur et Shareholders) et le `CurrentBinding` actif, sans exiger le droit sur D ni la présence du Pot dans `CurrentUserPotList`. La complétude d'une lecture autorisée d'une version active exige `PotBalance(P,V)` et les sous-versions pertinentes ; **la version terminale n'exige jamais de Balance**.

**Ordre de contrôle et non-divulgation :** authentifier l'EUI, contrôler le `bindingToken` présenté pour la lecture du Pot et le `CurrentBinding` actif ; dans READ, obtenir la version pertinente et établir les droits sur cette version avant tout `503` spécifique au Pot pour `minVersion` ou dépendance manquante. En courant : absence du Pot, droits insuffisants ou version terminale => `404` uniforme ; ensuite seulement vérifier `minVersion` et la complétude (sinon `503`). En historique : autoriser **à la version demandée**, jamais obligatoirement à la version courante ; version absente ou terminale ou non autorisée => `404`, puis vérifier les dépendances. Si la projection du Pot est encore absente de READ, aucune autorisation ne peut être démontrée : `404` et non un `503` qui révélerait l'existence WRITE. La révocation propagée en différé conserve la fenêtre asynchrone déjà admise ; la Supra READ ne consulte jamais WRITE synchroniquement. Une panne globale READ distincte de l'existence du Pot peut conduire à `503`.

Cet amendement complète SEC-01 et supplante les passages historiques de §§1–10 incohérents avec le droit historique et la suppression.

## 11. Reconstruction, coûts et preuves

Les historiques PotVersion, SubobjectVersion et PotBalance sont conservés sans archivage ni purge automatique V3. Le coût d'un manifeste complet par V est assumé et doit être mesuré, notamment pour les grands Pots (AUD-V3-013), sans modifier la règle au titre du présent audit. Reconstruction READ : arrêt des writers, reset cohérent des projections, slots et checkpoints et rejeu conforme à C-02 en [SPEC-05](SPEC-05_Consumption_Discovery.md). Preuves PRJ-01/02/03, READ-01, SEC-01, A10 et A11 en [SPEC-10](SPEC-10_Test_Certification.md).

## 12. CurrentInvitation, restitution et consultation invitée

`CurrentInvitation` est indépendante des huit autres projections. Elle contient l'identifiant, la version/état, le `creationRequestId`, la `creatorEui`, le `potId`, l'expiration, l'empreinte du token et le **lien restituable sous protection de confidentialité**. `MaterializeInvitation` consomme `INVITATION_EVENT`, lit WRITE à la version exacte et avance conditionnellement la projection, sans régression en cas d'événements hors ordre.

Le lien est restitué au seul `creatorEui` authentifié, par `creationRequestId`, jamais par `CommandResult`. Pour l'invité authentifié présentant le token, READ vérifie son empreinte, son expiration, le Pot actif à la version courante projetée et l'absence de rattachement à un Shareholder actif ; elle propose seulement les Shareholders `isFree` et leurs noms. `authorizedUserIds` ne suffit pas à détecter un rattachement, car cette liste contient aussi le créateur indépendant de tout Shareholder : conserver dans PotVersion un index interne des PocomaUserIds effectivement rattachés à au moins un Shareholder actif, distinct de authorizedUserIds et sans exposer userId. Cette information est reconstruite à V depuis WRITE et permet le contrôle d'éligibilité sans ajouter une projection. READ est éventuellement cohérent ; aucune place n'y est réservée.

