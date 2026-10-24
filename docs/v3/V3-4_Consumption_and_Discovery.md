# Pocoma V3-4 — Consumption, Discovery et Engine

**Statut : EN CONCEPTION — consolidation des arbitrages conversationnels, revue critique ouverte ; non implémenté et non certifié.**
**Référence :** [Canon et feuille de route](V3_Canon_and_Roadmap.md), [V3-2](V3-2_Events_Requests_and_Consumables.md), [V3-3](V3-3_Read_and_Projections.md).
**Règle d'or :** chaque mécanisme supplémentaire exige un invariant concret.

## 1. Identité et composition

Une Consumption associe un `consumptionId` stable, une famille source et une transformation. L'identité logique du traitement est `(consumptionId, consumableId)` ; aucun `workId` ajouté. Un **rejeu indépendant** est une nouvelle Consumption avec un nouvel ID ; l'Engine n'a pas de mode REPLAY. Une **reconstruction complète de READ à blanc**, avec remise à zéro cohérente, peut réutiliser les mêmes IDs. La coexistence de transformations visant une même projection est une responsabilité de composition applicative.

Un Worker compose librement des Consumptions burnables et non-burnables ; il partage les mécanismes Discovery et Engine, mais **pas une transaction unique** entre frontières physiques. Un même code de Consumption peut être déclaré par plusieurs Workers. Affectation de partitions **statique par configuration**, propriété effective durable exclusive. Plusieurs partitions logiques fixes par famille, indépendantes du nombre de Workers ; routage stable par `partitionHash` du consommable. Une partition peut être configurée sur plusieurs instances, mais une seule détient le droit effectif.

Deux boucles de polling adaptatif indépendantes (Discovery et Engine), coordonnées par les slots durables, sans LISTEN/NOTIFY. Round-robin entre Consumptions, capacité configurable par Consumption et Worker, sans priorité ni ordonnanceur de dépendances. L'ordre de traitement des slots n'est pas garanti ; la sélection doit éviter la famine dans les conditions de progression admissibles. Les collisions techniques sont acceptées ; la correction des effets, l'absence de pertes et la reprise sont obligatoires.

## 2. Discovery, sans Locator autonome

Discovery sélectionne **des références**, jamais les données métier complètes. Engine charge le consommable après acquisition. Pas de Locator autonome. Discovery **ne consulte pas les claims pour choisir les sources à découvrir** ; elle enregistre durablement l'enrôlement en créant les slots.

**Non-burnables :** quatre séquences globales transactionnelles denses conservées ; aucune séquence propre à la partition. Checkpoint de **découverte** par `(consumptionId, partitionId)`, absent = zéro. Il représente la dernière position globale examinée, même si certains événements ne concernent pas cette partition. À partir d'une borne sûre de publication, Discovery sélectionne seulement les événements de la partition dans une fenêtre bornée, puis crée les slots et avance atomiquement le checkpoint dans la base des effets. La lecture des références est préparatoire ; le contrôle du checkpoint, de la propriété et l'enrôlement sont transactionnels. Les journaux restent conservés, sans purge V3.

**Burnables :** demandes `COMMAND`, `IDENTITY_REQUEST` et tâches internes `POT_BALANCE_TASK`, `POT_VISIBILITY_TASK` ; statut source `PENDING → TERMINAL`. Discovery sélectionne les PENDING sans slot et crée des slots idempotents par lots bornés ; aucun checkpoint. Chaque Projection Task est destinée à une seule Consumption de matérialisation. Les tâches doivent être recréées avec de nouvelles identités quand la préparation est rejouée ; on ne rouvre pas une ancienne tâche terminale. Ce point exige une revue croisée avec V3-2/V3-3.

**Régulation :** plafond souple de slots présents par `(consumptionId, partitionId)`, mesuré directement en base, sans compteur durable. Le plafond concerne l'enrôlement, jamais la correction. Des lots bornés limitent la fenêtre de séquence et le nombre de slots.

## 3. Slots et preuve de complétion

Clé logique `(consumptionId, consumableId)`. Champs proposés :
- `partitionHash` stable ;
- `claimToken?`, `claimedUntil?` : tous deux NULL quand libre, tous deux renseignés quand claim présent ou expiré ;
- `retryAfter?` : délai de nouvelle tentative après échec transitoire.

Discovery crée le slot initial libre. Engine seul acquiert, renouvelle, libère ou supprime après complétion. Le token UUID change à chaque nouvelle acquisition, pas au renouvellement. Le statut de la source est la preuve de terminalisation des burnables. Pour les non-burnables, **checkpoint de découverte au moins égal à la position + absence du slot** est la preuve dérivée de complétion, sous réserve des invariants suivants :
1. checkpoint avancé uniquement atomiquement avec la création de *tous* les slots requis ;
2. suppression du slot seulement avec tous les effets durablement commités dans la même transaction physique ;
3. pas d'autre chemin de suppression, purge, nettoyage ou reconstruction dissociée ;
4. identité, routage et partitions stables ; progression de checkpoint monotone ;
5. pas de ré-enrôlement d'un événement situé à ou sous le checkpoint.

Pas de checkpoint de traitements terminés, ni de liste de positions terminées hors ordre. **Ne jamais inférer “terminé” de la seule absence d'un slot.** Pour un burnable, `PENDING` sans slot signifie non enrôlé ; `TERMINAL` prouve la fin.

## 4. Acquisition, protection et transactions

Slots dans la **même autorité transactionnelle que les effets** de la Consumption. Chaque tentative possède **une seule transaction physique finale**, couvrant la protection du slot, les validations métier, effets, preuve de complétion et suppression du slot. Pas de transaction distribuée. La transformation peut préparer ses calculs en dehors de cette transaction ; certaines lectures/décisions métier doivent néanmoins être réalisées dans la transaction finale, selon les invariants de V3-1.

**Partition :** un `ownerToken` UUID et une échéance durable, claim renouvelable. Ce token autorise **Discovery et l'acquisition de nouveaux slots**, pas la validation finale d'un claim préexistant. Acquisition/reprise conditionnelle et renouvellement sont protégés transactionnellement ; pas de `FOR KEY SHARE` de partition à chaque complétion. Un ancien propriétaire ne peut plus découvrir ni acquérir, mais peut finir un traitement dont il possède encore le claim individuel. Les détails CAS/verrous de takeover restent à prouver sous PostgreSQL.

**Slot :** acquisition conditionnelle, renouvellement conditionnel, protection exclusive finale. Prendre des lots bornés par capacité disponible, `SKIP LOCKED` + CAS, succès partiel, sans ordre FIFO, sans charger la source métier ni cache. `claimedUntil` et `retryAfter` restent distincts. Aucun `partitionOwnerToken` dans le slot : un nouveau propriétaire n'acquiert un ancien claim qu'après son expiration normale. Le renouvellement et la validation d'un claim déjà acquis sont indépendants de la propriété actuelle de partition. Un claim expiré ne peut pas être ressuscité par renouvellement.

Le claim doit être valide **à l'acquisition du verrou final**, lequel reste retenu jusqu'au commit ou rollback physique ; une expiration ultérieure n'annule pas ce commit. Perte de partition : arrêt des nouvelles Discovery et acquisitions et du renouvellement de la **propriété de partition** ; **les claims individuels encore détenus peuvent continuer à être renouvelés** et leurs calculs ne sont pas annulés. L'ancien Worker peut valider si son claim individuel est encore valide et n'a pas été repris. Le nouveau propriétaire peut reprendre un ancien claim **uniquement après son expiration normale** : la reprise et la validation finale du même slot sont sérialisées par sa protection exclusive. Aucune protection finale de partition n'est exigée.

Les CAS, modes de protection, ordre, et garanties de rollback doivent apparaître explicitement dans les ports et dans l'Engine applicatif, pas être cachés dans SQL ; ne pas créer un framework universel de locking.

## 5. Retries et recovery

Les rejets métier sont des résultats normaux et terminaux. **Protocole unique de reprise** : une acquisition de slot déclenche une tentative complète, avec rechargement des sources autoritatives et choix explicite de la version de Pot de la tentative ; la décision métier et les conditions initiales de la demande sont réévaluées sans réécriture de son intention. Une collision a posteriori (la version de travail n'est plus courante à la finalisation) est un échec technique de tentative, non un rejet métier automatique. Après tout échec transitoire : rollback complet, libération conditionnelle du claim et fixation de `retryAfter` dans le slot, puis nouvelle acquisition et **nouvelle exécution complète**. Aucun retry immédiat sous le même claim, aucun compteur de tentatives rapides, aucune boucle interne de retry ; temporisation simple configurable, sans backoff sophistiqué imposé. Si la cession du claim échoue ou que son issue est incertaine, Engine relit l'état durable ; il ne recrée jamais un slot absent. Tous les échecs techniques, même persistants, utilisent la même reprise temporisée ; les erreurs permanentes exigent diagnostic et intervention, sans fausse terminalisation ni statut durable supplémentaire. Claim individuel et propriété de partition renouvelables par un dispositif Engine centralisé ; renouvellement en lots bornés, succès partiel, transactions courtes par frontière physique. Sur renouvellement/commit d'issue inconnue, aucun état UNKNOWN durable : l'état persistant des slots et des sources fait autorité ; pas de double effet.

READ reconstruit **dans la même base**, avec indisponibilité temporaire possible. Pour une reconstruction totale à blanc, arrêter les writers READ et réinitialiser ensemble projections, tâches, slots et checkpoints, puis reprendre les mêmes Consumptions/IDs. Seul un rejeu indépendant nécessite un nouveau `consumptionId` ; il faut gérer explicitement la destination des effets et les tâches reproduites. On ne restaure pas des checkpoints séparément des slots et des effets qui les justifient.

## 6. Revue de complexité — questions ouvertes / risques majeurs

**R1 — Stockage et complétion des Projection Tasks (structurant).** Elles sont burnables à destinataire unique ; clarifier comment la préparation rejouée crée de nouveaux `taskId` malgré l'idempotence, et comment préserver les tâches/leurs résultats pendant une reconstruction READ. Ne pas altérer silencieusement les six familles *métier* de V3-2 ; décrire les deux familles *internes* séparément ou amender expressément V3-2.

**R2 — Invariant d'absence de slot.** Vérifier qu'une restauration, un changement de définition de Consumption, une collision entre anciennes et nouvelles exécutions ou une suppression administrative ne peut fabriquer `checkpoint >= position` sans effets. Vérifier le cas non-burnable de création simultanée d'une nouvelle Consumption.

**R3 — Simplification du fencing validée conceptuellement.** Le fencing final est **individuel par slot** ; le token de partition ne contrôle que Discovery et acquisition. Tester le chevauchement ancien/nouveau propriétaire, la course validation/reprise du même slot, les expirations et la correction des effets concurrents. Vérifier l'absence de verrou de partition lors de la complétion et la nécessité réelle des mécanismes de reprise immédiate.

**R4 — Disponibilité temporelle.** Utiliser une sémantique d'horloge DB correctement choisie : les transactions courtes, `now()` figé au début d'une transaction et les attentes de verrous peuvent produire une vérification d'expiration périmée. À préciser avant code.

**R5 — Coût du plafond et des sélections.** Comptage de slots sous backlog, `partitionHash`/partition fixe, préfixes globaux sûrs, nombreux slots réclamés/différés, indexation et absence de famine à mesurer. Un simple LIMIT sans ordre ne prouve pas l'équité.

**R6 — Mixité WRITE/READ.** Vérifier que toute Consumption et son slot/checkpoint/propriété de partition sont dans une transaction physique cohérente et qu'un Worker mixte peut effectivement router les opérations vers ces autorités sans transaction distribuée. La même notion de partition ne doit pas impliquer implicitement une même ligne physique de propriété dans plusieurs bases.

**R7 — Critère de simplicité du slot.** `partitionOwnerToken` et `sequencePosition` supprimés : reprise à expiration du claim, routage sans lecture de la source et position conservée uniquement dans l'événement. Réexaminer l'utilité du plafond COUNT ; ne conserver ces optimisations que si leur utilité est mesurable. La suppression des états de complétion redondants est acquise conceptuellement, mais nécessite preuve.

**R8 — Sémantique des burnables.** Une famille burnable destinée à une seule Consumption exclut un rejeu du même consommable terminal ; c'est un contrat distinct de celui des événements. Le statut des tâches internes et leur multiplicité doivent être explicités.

## 7. Prochaines vérifications et décision de statut

1. Revue contradictoire des invariants R1–R8, surtout preuve de « découvert et sans slot ⇒ terminé » et perte/reprise de partitions.
2. Élaguer les champs et opérations qui n'apportent pas de garantie démontrable.
3. Tracer les scénarios E2E WRITE → événements → READ et WRITE → tâches → READ, avec frontières transactionnelles.
4. Définir les preuves V3-5 : interleavings PostgreSQL, crash/UNKNOWN, ressources, backlog, latence, WAL et contention.
5. Revoir V3-2/V3-3 pour les deux sources internes, puis actualiser le canon.

**Amendements validés le 2026-10-23 :** `sequencePosition` est également supprimé du slot : Discovery et l'événement portent déjà cette information.  l'ownerToken est un droit d'enrôler/acquérir ; le claimToken est le droit de terminer. Le double fencing strict à la validation est supprimé. Le `partitionOwnerToken` disparaît du slot et les claims d'un ancien propriétaire restent valides jusqu'à leur expiration ou leur complétion, sans reprise anticipée. Preuve de concurrence et idempotence toujours ouverte.

**Amendement retry du 2026-10-23 :** suppression des retries immédiats au sein d'une acquisition ; une tentative = une acquisition et une exécution complète, reprise unique par `retryAfter` après libération conditionnelle.

**Aucune implémentation V3 avant V3-CADRE. Le présent document est un cadrage consolidé, pas une certification.**


## 8. Contrats consolidés avec V3-2 et V3-3 — 2026-10-23

1. **Huit familles au total :** les six familles métier/événementielles V3-2 et deux familles techniques burnables internes READ (`POT_BALANCE_TASK`, `POT_VISIBILITY_TASK`). Pas de nouvelles séquences événementielles. La préparation de chaque événement Pot crée **atomiquement les deux tâches en READ**, identifiées par `(preparationConsumptionId,potEventId,taskType)` ; elle supprime son propre slot dans cette même transaction. Chaque tâche terminalise sa source et supprime son slot atomiquement avec ses effets READ.
2. **Aucune coordination entre CurrentPot, balances et visibilité.** Les Consumers avancent indépendamment. READ vérifie les versions et composants effectivement nécessaires à chaque lecture, et n'invente aucune synchronisation globale des Pots de l'utilisateur.
3. **Visibilité :** stockage d'une entrée `(pocomaUserId,potId,potVersion,visible)` par couple ayant été visible au moins une fois, et contrôle `PotVisibilityState(potId,materializedVersion)`. Une transaction verrouille l'état, ignore une tâche obsolète, lit **seulement** les entrées couramment visibles, compare avec la liste complète fournie par la tâche, applique les `true`/`false`, avance la version puis terminalise/supprime le slot. L'initialisation de la ligne de contrôle doit être sûre sous concurrence. Pas d'historique exhaustif d'utilisateurs à parcourir ni de CAS individuel suffisant en remplacement du contrôle collectif.
4. **Sécurité READ :** authentification du porteur + `bindingToken` actif résolu via `CurrentBinding` vers `pocomaUserId`, puis autorisation d'accès à `Pot@V` via un Shareholder lié au User **dans cette version**. `CurrentUserPotList` reste une navigation éventuellement périmée. Les révocations d'identité n'entrent en vigueur sur READ qu'après propagation à `CurrentBinding` ; cette latence est acceptée. Le token ne vaut pas authentification autonome.
5. **Exécution :** une acquisition = une tentative complète, rechargement des sources et préconditions, aucun retry immédiat dans le claim ; échec technique connu → rollback, cession conditionnelle, `retryAfter`. Si le résultat du commit final est incertain, ne pas créer d'état UNKNOWN ni de slot : abandon local, puis reprise ordinaire selon l'état durable et l'expiration du claim. Les erreurs persistantes ne produisent jamais un résultat terminal fictif.
6. **Reconstruction READ :** reconstruction totale à blanc avec même `consumptionId`, après arrêt des writers et reset cohérent ; rejeu indépendant avec nouvel ID. Ré-exécution sur effets déjà satisfaits = terminalisation normale sans réécriture métier.

### Points encore à prouver / préciser avant V3-CADRE

- L'ordre exact des opérations de publication dense et des verrous finalisés, sans deadlock, sur commandes et demandes Identity ; le fencing temporel avec horloge PostgreSQL au moment de protection finale.
- La propriété atomique de l'ensemble de visibilité et l'initialisation concurrente du `PotVisibilityState` ; les voies d'écriture ne doivent jamais contourner sa protection.
- Les contraintes d'authentification du porteur du `bindingToken` et la preuve de contrôle/consentement de l'EUI cible lors du Link.
- Les scénarios de crash/reconstruction, l'intégrité checkpoint + slot + effets, la non-famine et les coûts SQL ; preuves exigées en V3-5.

## Amendement post-audit AUD-V3-004 — fenêtre sûre de Discovery (2026-10-24)

**Décision validée : conserver le modèle actuel et préciser l'algorithme, sans nouvel état durable ni nouvelle Consumption.** Les quatre séquences de publication sont transactionnelles et denses par famille : l'allocation de positions sous verrou tenu jusqu'au commit/rollback interdit qu'une position supérieure committe tandis qu'une position inférieure peut encore committer ultérieurement.

Pour chaque `(consumptionId, partitionId)`, Discovery :
1. lit le checkpoint de découverte `K` et détermine, dans la source d'événements committés, une **fenêtre globale contiguë et bornée** `(K,H]`, selon une photographie de lecture PostgreSQL cohérente ; le `H` retenu est effectivement couvert par cette lecture, pas un `MAX` arbitraire sur une autre photographie ;
2. retient dans cette fenêtre les événements relevant de la partition, tout en considérant les autres positions comme examinées ;
3. dans **une transaction de la base des effets**, valide l'ownership/fencing et le checkpoint attendu, enrôle idempotemment **tous** les slots requis pour cette partition et cette fenêtre puis avance `K` à `H` **dans le même commit** ;
4. en cas de conflit de checkpoint/ownership ou d'échec, ne valide aucun avancement partiel et recalcule au prochain essai.

**Point inter-bases :** la lecture préalable de la source WRITE peut se faire hors de la transaction d'enrôlement READ. L'exigence n'est **pas** « une seule requête SQL atomique couvrant WRITE et READ », mais : ne parcourir que des événements définitivement committés et immuables ; sélectionner une fenêtre cohérente ; traiter le checkpoint comme CAS/fencing avec création des slots atomique dans la base des effets. Les événements ne sont pas purgés dans le socle V3. Les règles de bornage et de progression par partition restent applicables.

**Preuves nécessaires :** deux publications dont la première garde le verrou avant commit et la seconde attend ; rollback de la première puis réallocation sans trou ; fenêtre incluant des événements d'autres partitions ; conflit entre Discoveries sur K/ownership ; crash après lecture source mais avant commit des slots ; READ/WRITE séparés ; checkpoint jamais avancé si un slot de la fenêtre manque. Aucun test V3 exécuté.

**Statut AUD-V3-004 : arbitrage conceptuel résolu ; preuve physique à réaliser en V3-6/7.**

## Amendement post-audit AUD-V3-005 — Consumption autonome pour chaque projection (2026-10-24)

**DÉCISION :** conserver l'association **Discovery + exécution au sein du Worker** pour chaque Consumption. Il n'y a ni Discovery globale distributrice de tâches, ni pipeline de préparation `PreparePotProjectionTasks`. `MaterializePotVersion`, `MaterializePotBalance` et `MaterializePotVisibility` sont trois Consumptions non-burnables autonomes de `POT_EVENT`, chacune avec ses checkpoints par partition, slots, revendications, fencing, transactions READ et retries. Une instance de Worker peut héberger les trois ; aucune séparation de déploiement n'est imposée.

Les seules familles burnables restantes sont `COMMAND` et `IDENTITY_REQUEST`. Toutes les règles de Discovery non-burnable (fenêtre sûre, passage du checkpoint et création de slots atomiques dans la base des effets) s'appliquent **individuellement** à chacune des trois Consumptions. Les effets de chaque projecteur et la fin de son slot restent atomiques dans READ.

`MaterializePotVisibility` calcule elle-même depuis WRITE@V la liste complète des utilisateurs visibles ; l'ancienne tâche `PotVisibilityTask(visibleUserIds)` n'existe plus. Elle conserve la garde transactionnelle `PotVisibilityState(P)` pour appliquer le différentiel sans écrasement par des événements hors ordre. `MaterializePotBalance` calcule les balances depuis WRITE@V, sans tâche portant les balances pré-calculées. `MaterializePotVersion` conserve les garanties d'immuabilité/pointeur.

**Supplante** les références historiques aux deux tâches burnables internes et à leur préparation. AUD-V3-006 (opportunité de créer directement les slots de demandes à l'admission) est **distinct et non arbitré**.

## Amendement post-audit AUD-V3-006 — admission sans création de slot (2026-10-24)

**Arbitrage validé : REJET de la création synchrone d'un slot dans l'admission.** Le Supra authentifie, valide la forme et l'idempotence, puis persiste la Command ou IdentityRequest `PENDING` (et son association idempotente) dans WRITE, sans créer ni revendiquer de slot. L'admission ne doit pas attendre les travaux d'enrôlement du moteur.

**Discovery burnable maintenue :** recherche asynchrone, bornée et idempotente des demandes `PENDING` sans slot, puis création des slots selon le protocole normal ; aucune fenêtre de perte entre admission et découverte puisque la source durable reste PENDING. La transaction d'admission ne coordonne pas le cycle de vie de l'exécution. Pour les familles de projection supprimées par AUD-V3-005, il n'existe plus de Discovery burnable de tâches READ : seules COMMAND et IDENTITY_REQUEST demeurent.

**Compromis assumé :** une étape et une latence de prise en charge supplémentaires contre un chemin d'admission minimal, découplé des aléas de Discovery et des contraintes des slots. Mesurer le délai admission→claim et le coût de sélection PENDING avant toute réouverture de cette décision.

## Rectification AUD-V3-007 — aucune quarantaine en V3 (2026-10-24)

**RECTIFICATION NORMATIVE :** le précédent amendement AUD-V3-007 qui prétendait exclure `QUARANTINED` du softCap est **retiré** : ce statut n'existe pas dans le contrat V3. La règle de reprise établie plus haut prévaut : échecs techniques sans limite de tentatives, une exécution par acquisition, cession conditionnelle, `retryAfter` et nouvelle acquisition complète ; erreurs persistantes diagnostiquées sans terminalisation ni état supplémentaire. Le rapport indépendant a analysé un mécanisme de quarantaine étranger au modèle retenu.

**softCap :** il borne le nombre de slots présents dans la partition, y compris ceux différés par `retryAfter`. Ne pas inventer un filtrage de statuts. Le risque d'une accumulation durable de slots avec erreurs persistantes reste une **question distincte de capacité et de progression** à éprouver sous charge, et ne justifie pas à lui seul une quarantaine.

**AUD-V3-007 : constat fondé sur `QUARANTINED` NON APPLICABLE ; tests réels de non-famine et de capacité restent requis.**

## Nouvel arbitrage AUD-V3-007 — retries bornés et quarantaine (2026-10-24)

**DÉCISION DE PRINCIPE RÉVISÉE :** remplacer le retry technique illimité par un budget configurable de **N tentatives complètes d'exécution**. Une tentative = une acquisition et une exécution ; chaque échec technique entraîne rollback puis, tant que le budget n'est pas épuisé, cession conditionnelle avec `retryAfter`. À épuisement, le slot devient `QUARANTINED`, n'est plus automatiquement acquis, et **ne compte plus dans le `softCap`**. Cette décision supplante les anciens amendements « retries illimités, pas de quarantaine » ; le motif est l'isolation de la capacité d'enrôlement entre Pots indépendants.

**Garantie non négociable :** la quarantaine n'est pas une complétion et ne doit jamais faire croire que l'effet est produit. Pour les non-burnables, checkpoint déjà avancé + slot absent ne doit jamais être fabriqué par une purge prématurée ; pour les burnables, la demande PENDING doit demeurer rattachée à sa preuve de quarantaine afin que Discovery ne la réenrôle pas comme neuve. Les slots en quarantaine persistent tant qu'aucun protocole sûr de résolution/purge n'a été décidé.

**À arbitrer avant implémentation :** valeur de N et comptabilisation des crashs/expirations versus échecs techniques connus ; stratégie de relance manuelle ou de réparation ; purge après résolution prouvée sans perte ni ré-enrôlement ; maintien de la progression des travaux causalement dépendants (ne pas laisser un successeur utiliser un état incorrect, tout en laissant avancer les Pots indépendants). Pas de terminalisation métier fictive ni de suppression automatique d'un travail non exécuté.

**Statut : quarantaine et exclusion softCap validées conceptuellement ; purge automatique et critères de résolution NON validés.**

## Complément AUD-V3-007-Q — reprise de quarantaine exclusivement explicite (2026-10-24)

**Décision :** un slot `QUARANTINED` ne redevient **jamais** éligible du seul fait d'un redéploiement, d'un redémarrage, d'une expiration temporelle ou d'une nouvelle Discovery. La reprise nécessite une **action administrative explicite**, distincte de l'exécution ordinaire.

**Contrat minimal :** l'action cible sans ambiguïté le slot, vérifie durablement son état `QUARANTINED`, et change atomiquement son état pour le rendre à nouveau éligible, sous protection concurrente appropriée (CAS/transaction). Elle ne prétend pas que le traitement a réussi, n'altère ni la source ni les événements, n'ajoute pas d'effet READ/WRITE et n'exécute pas la projection elle-même. La prochaine acquisition ordinaire relit la source et reconstruit le contexte, comme tout retry. Le budget de tentatives du nouveau cycle et les éventuels détails de traçabilité seront définis au cadrage final ; aucune relance automatique en masse.

**Maintien des invariants :** le slot reste présent en quarantaine et exclu du softCap, il ne peut être découvert à nouveau comme un nouveau travail ; une fois réactivé, il redevient soumis au softCap et au protocole normal. Toute purge devra être justifiée séparément par une preuve de complétion ou un protocole sûr, sans assimiler quarantaine et terminalité.

## Complément AUD-V3-007-Q — budget et expirations de claim (2026-10-24)

**Deux règles validées :** chaque slot porte un compteur `attemptCount` des **échecs techniques explicitement constatés et durablement enregistrés** ; le seuil `N` de tentatives est configurable. Lors d'une reprise administrative explicite d'un slot `QUARANTINED`, la même transaction de transition réinitialise `attemptCount = 0`, sans changer l'identité du slot ni recréer le travail.

**Expiration de claim :** une expiration de bail, même suivie d'une réacquisition, n'est **pas** à elle seule un échec enregistré et ne modifie pas `attemptCount`. Une tentative sans issue durable connue ne peut consommer le budget par simple supposition. L'enregistrement de l'échec technique, son comptage et la transition conditionnelle vers `retryAfter` ou `QUARANTINED` doivent être protégés contre les écritures tardives d'un ancien `claimToken` et les doubles comptages. Le succès supprime le slot selon le protocole existant.

**Restent à spécifier :** valeur/configuration de N, contrat d'erreurs et de cession durable en cas d'échec, cohérence exacte entre compteur et claim lors de races, comportement des dépendances causales et règles de purge. Aucun changement implémenté.

## Complément AUD-V3-007-Q — indépendance des slots (2026-10-24)

**Arbitrage validé : aucune dépendance d'exécution entre slots.** Une quarantaine ne bloque **aucun autre slot**, y compris pour le même Pot/EUI et pour une version ultérieure. Les Consumptions reconstituent les faits autoritatifs correspondant à leur version ; les projections immuables sont produites par version, les projections mutables appliquent des gardes monotones (notamment `PotVisibilityState` et `CurrentBinding`). Aucun ordonnancement causal ni graphe de dépendances n'est introduit dans Engine ou Discovery. Les mentions antérieures d'un blocage des « successeurs causalement dépendants » sont **supplantées**.

**Distinction :** la possibilité de lire un Pot complet à V suppose que ses différentes projections nécessaires à V soient matérialisées, mais n'établit aucune dépendance d'exécution entre leurs slots. La quarantaine conserve son slot et l'absence de sa projection ; elle ne doit jamais être interprétée comme une complétion.

**Point de certification :** vérifier, dans chaque Consumption y compris Command et IdentityRequest, l'absence effective de précondition « slot précédent terminé » et la correction des effets hors ordre. Cet arbitrage n'autorise pas à négliger les contrôles de cohérence/versions au moment de l'exécution.

## Complément AUD-V3-007-Q — purge uniquement après succès (2026-10-24)

**Arbitrage définitif de cycle de vie :** un slot `QUARANTINED` n'est **jamais purgé directement**. Il reste durablement présent comme preuve d'un traitement incomplet, même sur une durée indéfinie. Il n'occupe pas le `softCap` et ne bloque aucun autre slot. Sa seule sortie vers le traitement est la réactivation administrative explicite : même identité de slot, `attemptCount=0`, retour à l'éligibilité normale. Après acquisition et **succès effectif**, la transaction de finalisation écrit les effets et supprime atomiquement le slot selon le protocole ordinaire. Pas de terminalisation artificielle ni de suppression administrative d'un travail incomplet.

**Conséquences :** pour les sources non-burnables, un checkpoint déjà avancé ne doit jamais masquer un travail en quarantaine ; pour les sources burnables, la demande demeure PENDING avec son slot durable et Discovery ne doit pas la recréer. L'archivage de long terme, s'il devient utile, constitue un sujet ultérieur distinct qui ne peut violer ces garanties.

**AUD-V3-007/Q : cycle conceptuel clos** — seuil N configurable (sa valeur opérationnelle reste un paramètre), erreur technique comptée une fois sous claim courant, expiration non comptée, reprise administrative explicite, indépendance totale entre slots et suppression après succès exclusivement. Certification PostgreSQL encore à exécuter ; aucune implémentation V3 engagée.
