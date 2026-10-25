# Pocoma SPEC-05 — Consumption et Discovery

> **Lecture des statuts (clôture L1, 2026-10-25) :** les mentions de non-implémentation/non-exécution ci-dessous décrivent l’état du cadrage conceptuel lors de sa rédaction, pas un relevé actuel des tests. Les contrats et oracles restent normatifs ; contributions internes L1 et limites dans le [dossier de clôture](L1_Documentary_Closure.md), programme et statuts dans le [plan directeur](V3_Implementation_Master_Plan.md). Aucun PASS global de cette SPEC n’est acquis par cette note.

**Statut : EN CONCEPTION — consolidation des arbitrages conversationnels, revue critique ouverte ; non implémenté et non certifié.**
**Référence :** [Canon et feuille de route](V3_Canon_and_Roadmap.md), [V3-2](SPEC-03_Events_Requests_Consumables.md), [V3-3](SPEC-04_Read_Projections.md).
**Règle d'or :** chaque mécanisme supplémentaire exige un invariant concret.

## 1. Identité et composition

Une Consumption associe un `consumptionId` stable, une famille source et une transformation. L'identité logique du traitement est `(consumptionId, consumableId)` ; aucun `workId` ajouté. Un **rejeu indépendant** est une nouvelle Consumption avec un nouvel ID ; l'Engine n'a pas de mode REPLAY. Une **reconstruction complète de READ à blanc**, avec remise à zéro cohérente, peut réutiliser les mêmes IDs. La coexistence de transformations visant une même projection est une responsabilité de composition applicative.

Un Worker compose librement des Consumptions burnables et non-burnables ; il partage les mécanismes Discovery et Engine, mais **pas une transaction unique** entre frontières physiques. Un même code de Consumption peut être déclaré par plusieurs Workers. Affectation de partitions **statique par configuration**, propriété effective durable exclusive. Plusieurs partitions logiques fixes par famille, indépendantes du nombre de Workers ; routage stable par `partitionHash` du consommable. Une partition peut être configurée sur plusieurs instances, mais une seule détient le droit effectif.

Deux boucles de polling adaptatif indépendantes (Discovery et Engine), coordonnées par les slots durables, sans LISTEN/NOTIFY. Round-robin entre Consumptions, capacité configurable par Consumption et Worker, sans priorité ni ordonnanceur de dépendances. L'ordre de traitement des slots n'est pas garanti ; la sélection doit éviter la famine dans les conditions de progression admissibles. Les collisions techniques sont acceptées ; la correction des effets, l'absence de pertes et la reprise sont obligatoires.

## 2. Discovery, sans Locator autonome

Discovery sélectionne **des références**, jamais les données métier complètes. Engine charge le consommable après acquisition. Pas de Locator autonome. Discovery **ne consulte pas les claims pour choisir les sources à découvrir** ; elle enregistre durablement l'enrôlement en créant les slots.

**Non-burnables :** cinq séquences globales transactionnelles denses conservées ; aucune séquence propre à la partition. Checkpoint de **découverte** par `(consumptionId, partitionId)`, absent = zéro. Il représente la dernière position globale examinée, même si certains événements ne concernent pas cette partition. À partir d'une borne sûre de publication, Discovery sélectionne seulement les événements de la partition dans une fenêtre bornée, puis crée les slots et avance atomiquement le checkpoint dans la base des effets. La lecture des références est préparatoire ; le contrôle du checkpoint, de la propriété et l'enrôlement sont transactionnels. Les journaux restent conservés, sans purge V3.

**Burnables :** uniquement `COMMAND` et `IDENTITY_REQUEST`, admises durablement PENDING sans slot ; Discovery ultérieure sélectionne les PENDING sans slot et crée les slots idempotents par lots bornés, sans checkpoint. Aucune tâche READ burnable ni création synchrone de slot à l'admission (AUD-V3-005/006).

**Régulation :** plafond souple des slots non-QUARANTINED présents par `(consumptionId, partitionId)`, mesuré directement en base, sans compteur durable. Le plafond concerne l'enrôlement, jamais la correction. Des lots bornés limitent la fenêtre de séquence et le nombre de slots.

## 3. Slots et preuve de complétion

Clé logique `(consumptionId, consumableId)`. Champs proposés :
- `partitionHash` stable ;
- `claimToken?`, `claimedUntil?` : tous deux NULL quand libre, tous deux renseignés quand claim présent ou expiré ;
- `retryAfter?` : délai de reprise après échec technique ;
- `attemptCount` : nombre durable d'échecs techniques constatés ; état `QUARANTINED` exclu du softCap.

Discovery crée le slot initial libre. Engine seul acquiert, renouvelle, libère ou supprime après complétion. Le token UUID change à chaque nouvelle acquisition, pas au renouvellement. Le statut de la source est la preuve de terminalisation des burnables. Pour les non-burnables, **checkpoint de découverte au moins égal à la position + absence du slot** est la preuve dérivée de complétion, sous réserve des invariants suivants :
1. checkpoint avancé uniquement atomiquement avec la création de *tous* les slots requis ;
2. suppression du slot seulement avec tous les effets durablement commités dans la même transaction physique ;
3. pas d'autre chemin de suppression, purge, nettoyage ou reconstruction dissociée ;
4. identité, routage et partitions stables ; progression de checkpoint monotone ;
5. pas de ré-enrôlement d'un événement situé à ou sous le checkpoint.

Pas de checkpoint de traitements terminés, ni de liste de positions terminées hors ordre. **Ne jamais inférer “terminé” de la seule absence d'un slot.** Pour un burnable, `PENDING` sans slot signifie non enrôlé ; `TERMINAL` prouve la fin.

## 4. Acquisition, protection et transactions

Slots dans la **même autorité transactionnelle que les effets** de la Consumption. Chaque tentative possède **une seule transaction physique finale**, couvrant la protection du slot, les validations métier, effets, preuve de complétion et suppression du slot. Pas de transaction distribuée. La transformation peut préparer ses calculs en dehors de cette transaction ; certaines lectures/décisions métier doivent néanmoins être réalisées dans la transaction finale, selon les invariants de V3-1. Pour une `IdentityRequest` impliquant deux EUI distinctes (`Link(E1, E2)` ou `Unlink(E1, E2)` avec `E1 != E2`), la transaction WRITE finale acquiert un verrou exclusif (`SELECT ... FOR UPDATE`, après création de la ligne EUI si inconnue) sur `E1` et `E2` dans **l'ordre lexicographique canonique de leur clé `(issuer, subject)`** avant de valider les préconditions et d'écrire la transition, prévenant ainsi tout deadlock et toute course inter-partitions.

**Partition :** un `ownerToken` UUID et une échéance durable, claim renouvelable. Ce token autorise **Discovery et l'acquisition de nouveaux slots**, pas la validation finale d'un claim préexistant. Acquisition/reprise conditionnelle et renouvellement sont protégés transactionnellement ; pas de `FOR KEY SHARE` de partition à chaque complétion. Un ancien propriétaire ne peut plus découvrir ni acquérir, mais peut finir un traitement dont il possède encore le claim individuel. Les détails CAS/verrous de takeover restent à prouver sous PostgreSQL.

**Slot :** acquisition conditionnelle, renouvellement conditionnel, protection exclusive finale. Prendre des lots bornés par capacité disponible, `SKIP LOCKED` + CAS, succès partiel, sans ordre FIFO, sans charger la source métier ni cache. `claimedUntil` et `retryAfter` restent distincts. Aucun `partitionOwnerToken` dans le slot : un nouveau propriétaire n'acquiert un ancien claim qu'après son expiration normale. Le renouvellement et la validation d'un claim déjà acquis sont indépendants de la propriété actuelle de partition. Un claim expiré ne peut pas être ressuscité par renouvellement.

Le claim doit être valide **à l'acquisition du verrou final**, lequel reste retenu jusqu'au commit ou rollback physique ; une expiration ultérieure n'annule pas ce commit. Perte de partition : arrêt des nouvelles Discovery et acquisitions et du renouvellement de la **propriété de partition** ; **les claims individuels encore détenus peuvent continuer à être renouvelés** et leurs calculs ne sont pas annulés. L'ancien Worker peut valider si son claim individuel est encore valide et n'a pas été repris. Le nouveau propriétaire peut reprendre un ancien claim **uniquement après son expiration normale** : la reprise et la validation finale du même slot sont sérialisées par sa protection exclusive. Aucune protection finale de partition n'est exigée.

Les CAS, modes de protection, ordre, et garanties de rollback doivent apparaître explicitement dans les ports et dans l'Engine applicatif, pas être cachés dans SQL ; ne pas créer un framework universel de locking.

## 5. Retries et recovery

Les rejets métier sont des résultats normaux et terminaux. **Protocole unique de reprise :** une acquisition déclenche une exécution complète, sans retry interne au claim. Après échec technique confirmé, rollback intégral, comptabilisation durable et unique de l'échec sous le claim valide, puis `retryAfter` tant que `attemptCount < N` ; au N-ième échec technique constaté, passage durable à `QUARANTINED`. Le seuil N est configurable. L'expiration du claim, un crash sans issue durable constatée et un commit incertain ne consomment pas le budget par supposition. Les rejets métier restent terminaux normaux, jamais de fausse terminalisation. Un claim perdu ne peut enregistrer tardivement ni doubler un échec. La reprise ordinaire recharge la source, les versions et les préconditions sans altérer l'intention.

Claim individuel et propriété de partition renouvelables par un dispositif Engine centralisé ; renouvellement en lots bornés, succès partiel, transactions courtes par frontière physique. Sur renouvellement/commit d'issue inconnue, aucun état UNKNOWN durable : l'état persistant des slots et des sources fait autorité ; pas de double effet.

READ reconstruit **dans la même base**, avec indisponibilité temporaire possible. Pour une reconstruction totale à blanc, arrêter les writers READ et réinitialiser ensemble projections, slots et checkpoints, puis reprendre les mêmes Consumptions/IDs. Seul un rejeu indépendant nécessite un nouveau `consumptionId` ; il faut gérer explicitement la destination des effets et les effets produits. On ne restaure pas des checkpoints séparément des slots et des effets qui les justifient.


## 6. Fenêtre sûre et admission (AUD-V3-004/006)

Sur chacune des cinq séquences d'événements transactionnellement denses, la Discovery non-burnable lit le checkpoint K puis détermine une fenêtre contiguë bornée (K,H] d'événements définitivement committés, selon une photographie PostgreSQL cohérente. H est couvert par cette lecture, sans MAX issu d'une autre photographie. La Discovery retient uniquement les événements de la partition mais considère les autres positions de la fenêtre comme examinées. Dans la base des effets, elle valide atomiquement ownership/fencing et checkpoint attendu, crée idempotemment tous les slots requis puis avance K à H dans le même commit ; tout conflit ou échec annule l'ensemble. La lecture source WRITE préalable peut être hors de cette transaction READ : aucune transaction distribuée n'est requise.

Les demandes burnables sont admises PENDING en WRITE sans création de slot par la Supra ; Discovery les enrôle ultérieurement et de façon indépendante. Les sources non-burnables ne sont pas purgées en V3.

## 7. Quarantaine et cycle de vie (AUD-V3-007)

`QUARANTINED` est un état durable de slot non terminé, jamais une complétion. Le slot reste présent, n'est ni acquis automatiquement ni compté dans le softCap, et n'est jamais recréé par Discovery. Il ne bloque aucun autre slot, y compris une version ultérieure du même Pot/EUI et les autres Consumptions. Aucune dépendance inter-slots ou graphe causal n'est imposé.

Seule une action administrative explicite et atomique sur le même slot QUARANTINED le rend à nouveau éligible et remet `attemptCount=0`. Ni redémarrage, ni temps écoulé, ni nouveau déploiement ne le réactivent. La réactivation ne crée aucun effet métier ; le traitement reprend par acquisition normale. Aucun slot incomplet, même durablement en quarantaine, n'est jamais purgé : seule la finalisation réussie écrit les effets et supprime atomiquement son slot. Pour une source burnable la demande demeure PENDING, pour une source non-burnable son checkpoint peut être avancé mais le slot persiste.

## 8. Consumptions directes et indépendantes (AUD-V3-005)

Chacun des `POT_EVENT(P,V)` déclenche trois Consumptions non-burnables distinctes, chacune avec Discovery, checkpoint, slots, Engine, fencing et transaction READ propres : `MaterializePotVersion`, `MaterializePotBalance`, `MaterializePotVisibility`. Aucun `PreparePotProjectionTasks`, aucune tâche intermédiaire ou orchestration centrale. Le même Worker physique peut héberger ces traitements, sans les coupler.

Chaque Consumption lit les faits autoritatifs correspondant à V depuis WRITE et calcule ses propres effets ; `MaterializePotVisibility` utilise la garde collective transactionnelle `PotVisibilityState(P)` pour appliquer les différences monotones, sans reculer sur des événements arrivant hors ordre ; `MaterializePotBalance` calcule les soldes complets de chaque version active et considère D terminale comme un succès sans insertion Balance(D) ; `MaterializePotVersion` conserve les versions immuables et avance conditionnellement le pointeur. Les contrats détaillés des projections relèvent de [V3-3](SPEC-04_Read_Projections.md).

Les accès à WRITE de `MaterializePotVersion` et de `MaterializePotBalance` sont indépendants : le premier résout toute la composition à V et ne charge que les contenus de sous-versions absents de READ ; le second lit par lots **l'ensemble** des faits financiers applicables à V et recalcule intégralement. Les lectures groupées ne mutualisent ni slot, ni transaction, ni état de progression entre Consumptions. Le contrôle d'existence d'une `SubobjectVersion` déjà immuable n'instaure aucune attente d'une version antérieure ; une absence déclenche le chargement direct depuis WRITE@V. Voir SPEC-04 (AUD-V3-013).

## 9. Reconstruction et vérification

La reconstruction READ totale implique indisponibilité contrôlée, arrêt effectif des writers READ, reset coordonné des projections, slots et checkpoints, puis reprise des mêmes IDs de Consumption et rejeu avant disponibilité READY selon C-02. Un rejeu indépendant utilise un nouvel ID. Ne jamais restaurer un checkpoint sans les effets et slots qui le justifient.

Restent à certifier (et non à arbitrer une seconde fois) : horloge PostgreSQL au verrou final, takeover concurrent, absence de deadlock, conflit de borne sûre, transaction physique, rattrapage sans famine, coût SQL, crashs et quarantaine. Voir [V3-5D](SPEC-10_Test_Certification.md), CON-01 à CON-06, REC-01 et OPS-01. Aucun code, DDL ou test V3 exécuté.

## 10. MaterializeInvitation

`MaterializeInvitation` est une Consumption READ indépendante alimentée par la cinquième séquence événementielle `INVITATION_EVENT`. Elle utilise les mêmes Discovery, slots, checkpoint, claim, fencing, retry et quarantaine que les autres Consumptions. Pour chaque `(invitationId, invitationVersion)`, elle relit l'état WRITE exact puis matérialise `CurrentInvitation` et termine son slot atomiquement en READ. Aucun prérequis de `CommandResult`, `PotVersion` ou `MaterializePotVisibility`. Une version ancienne ne fait jamais régresser la projection ; la reconstruction READ rejoue également cette famille.

## 11. Passage du socle Command L1 aux slots L2

Le socle interne L1 emploie temporairement `v3_command_slot(request_id)`. L2 doit **remplacer ce modèle**, en le réunissant avec la clé canonique `ConsumptionSlot(consumptionId, consumableId)` ; pour la Consumption Command, consumableId désigne requestId. Il ne faut ni pérenniser ce slot dédié comme second moteur ni lui adjoindre un autre protocole de reprise. Aucune migration n'est exécutée en L1-F.

Le cadrage L2 doit inventorier tous les écrivains et désactiver l'ancien propriétaire avant bascule. Il doit préciser la transformation des slots existants et claims/quarantaines, ou établir une installation neuve sans état à transférer. Ne pas perdre les demandes PENDING ni recréer des slots pour une demande TERMINAL. Les fixtures d'acquisition/autorité L1 ne constituent pas le moteur futur.

Garanties L1 à préserver et à réexécuter sur le pilote réel : une acquisition/une tentative complète ; intention/observedVersion immuables ; verrou exclusif final du slot puis horloge PostgreSQL fraîche ; token et échéance valides au verrou, protection jusqu'au commit ; Identity distincte du Pot (y compris témoin négatif sans Pot pour INVALID_BINDING) ; CAS Pot et réévaluation métier entière après collision ; même datasource/transaction physique pour effets, résultat, complétion, publication et suppression du slot ; writer_xid complet, poisoning et rollback même après erreur interceptée ; aucune écriture métier après suffixe dense ; zéro double effet/complétion sous concurrence et commit incertain ; comptabilisation unique après rollback confirmé, retryAfter, N configurable, quarantaine persistante et réactivation explicite ; reprise d'un résultat terminal inchangé.

Le protocole commun doit couvrir Command et un pilote READ non-burnable avec slots/checkpoints dans leurs bases d'effets respectives. Discovery, ownership, fencing d'acquisition et renouvellement complètent ces acquis ; aucune transaction distribuée ni dépendance entre slots n'est permise. Les oracles CON-01…06, EVT-01…03 et le contrat transactionnel L1-C sont les prérequis du plan L2, pas des preuves déjà acquises du moteur générique.

**Prérequis complémentaire L1-F2 :** avant l’implémentation L2, valider le lot correctif WRITE et son audit. Le pilote Command devra préserver les accès directs aux versions/révisions concernées, les préconditions locales et la fermeture exacte atomique, sans réintroduire une lecture de chaîne historique par tentative. L’auditeur exhaustif reste une opération de maintenance explicite. [Matrice WRITE](L1_F2_Invariant_Matrix.md) ; statuts et conditions d’entrée dans le plan directeur.
