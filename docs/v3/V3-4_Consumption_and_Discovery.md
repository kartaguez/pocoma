# Pocoma V3-4 — Consumption, Discovery et Engine

**Statut : EN CONCEPTION — consolidation des arbitrages conversationnels, revue critique ouverte ; non implémenté et non certifié.**
**Référence :** [Canon et feuille de route](V3_Canon_and_Roadmap.md), [V3-2](V3-2_Events_Requests_and_Consumables.md), [V3-3](V3-3_Read_and_Projections.md).
**Règle d'or :** chaque mécanisme supplémentaire exige un invariant concret.

## 1. Identité et composition

Une Consumption associe un `consumptionId` stable, une famille source et une transformation. L'identité logique du traitement est `(consumptionId, consumableId)` ; aucun `workId` ajouté. Un rejeu est **une nouvelle Consumption, avec un nouvel ID** ; l'Engine n'a pas de mode REPLAY. La coexistence de transformations visant une même projection est une responsabilité de composition applicative.

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
- `sequencePosition?` pour les non-burnables ;
- `partitionOwnerToken?`, `claimToken?`, `claimedUntil?` : tous NULL quand libre, tous renseignés quand claim présent ou expiré ;
- `retryAfter?` : délai de nouvelle tentative après échec transitoire.

Discovery crée le slot initial libre. Engine seul acquiert, renouvelle, libère ou supprime après complétion. Le token UUID change à chaque nouvelle acquisition, pas au renouvellement. Le statut de la source est la preuve de terminalisation des burnables. Pour les non-burnables, **checkpoint de découverte au moins égal à la position + absence du slot** est la preuve dérivée de complétion, sous réserve des invariants suivants :
1. checkpoint avancé uniquement atomiquement avec la création de *tous* les slots requis ;
2. suppression du slot seulement avec tous les effets durablement commités dans la même transaction physique ;
3. pas d'autre chemin de suppression, purge, nettoyage ou reconstruction dissociée ;
4. identité, routage et partitions stables ; progression de checkpoint monotone ;
5. pas de ré-enrôlement d'un événement situé à ou sous le checkpoint.

Pas de checkpoint de traitements terminés, ni de liste de positions terminées hors ordre. **Ne jamais inférer “terminé” de la seule absence d'un slot.** Pour un burnable, `PENDING` sans slot signifie non enrôlé ; `TERMINAL` prouve la fin.

## 4. Acquisition, protection et transactions

Slots dans la **même autorité transactionnelle que les effets** de la Consumption. Chaque tentative possède **une seule transaction physique finale**, couvrant protections de partition et slot, validations métier, effets, preuve de complétion et suppression du slot. Pas de transaction distribuée. La transformation peut préparer ses calculs en dehors de cette transaction ; certaines lectures/décisions métier doivent néanmoins être réalisées dans la transaction finale, selon les invariants de V3-1.

**Partition :** un `ownerToken` UUID et une échéance durable, claim renouvelable. Protocole applicatif explicite : takeover sous CAS et `FOR UPDATE` ; renouvellement CAS de colonnes non-clés ; validation protégée par `FOR KEY SHARE` jusqu'à résolution physique. **À prouver** : compatibilité réelle des verrous sous la configuration et les contraintes SQL retenues ; un UPDATE ordinaire non-clé ne verrouille pas nécessairement comme un takeover. Ordre imposé des protections : partition puis slot.

**Slot :** acquisition conditionnelle, renouvellement conditionnel, protection exclusive finale. Prendre des lots bornés par capacité disponible, `SKIP LOCKED` + CAS, succès partiel, sans ordre FIFO, sans charger la source métier ni cache. `claimedUntil` et `retryAfter` restent distincts. `partitionOwnerToken` enregistré permet de reprendre immédiatement un claim d'ancien propriétaire, avec contrôle transactionnel du propriétaire courant, en conservant `retryAfter`. Les sélections peuvent comparer au token connu du Worker, sans jointure systématique à la table des partitions.

Le claim doit être valide **à l'acquisition du verrou final**, lequel reste retenu jusqu'au commit ou rollback physique ; une expiration ultérieure n'annule pas ce commit. Perte de partition : arrêt des nouvelles Discovery, acquisitions et renouvellements ; **aucune obligation d'annuler** les calculs en cours ; le double fencing refuse les validations illégitimes. Une transaction déjà protégée peut committer avant que le takeover n'aboutisse.

Les CAS, modes de protection, ordre, et garanties de rollback doivent apparaître explicitement dans les ports et dans l'Engine applicatif, pas être cachés dans SQL ; ne pas créer un framework universel de locking.

## 5. Retries et recovery

Les rejets métier sont des résultats normaux et terminaux. Erreurs techniques transitoires : rollback intégral, retries immédiats bornés puis `retryAfter` durable, sans plafond définitif de reprises différées si les conditions redeviennent favorables. Erreurs permanentes : diagnostic et intervention nécessaires, sans fausse terminalisation. Claim individuel et propriété de partition renouvelables par un dispositif Engine centralisé ; renouvellement en lots bornés, succès partiel, transactions courtes par frontière physique. Sur renouvellement/commit d'issue inconnue, aucun état UNKNOWN durable : relire l'autorité, reprendre avec les mêmes tokens seulement si encore admissible, vérifier la preuve de complétion avant de réexécuter.

READ reconstruit **dans la même base**, avec indisponibilité temporaire possible. Un rejeu utilise un nouvel `consumptionId` ; il faut gérer explicitement la destination des effets et les tâches reproduites. On ne restaure pas des checkpoints séparément des slots et des effets qui les justifient.

## 6. Revue de complexité — questions ouvertes / risques majeurs

**R1 — Stockage et complétion des Projection Tasks (structurant).** Elles sont burnables à destinataire unique ; clarifier comment la préparation rejouée crée de nouveaux `taskId` malgré l'idempotence, et comment préserver les tâches/leurs résultats pendant une reconstruction READ. Ne pas altérer silencieusement les six familles *métier* de V3-2 ; décrire les deux familles *internes* séparément ou amender expressément V3-2.

**R2 — Invariant d'absence de slot.** Vérifier qu'une restauration, un changement de définition de Consumption, une collision entre anciennes et nouvelles exécutions ou une suppression administrative ne peut fabriquer `checkpoint >= position` sans effets. Vérifier le cas non-burnable de création simultanée d'une nouvelle Consumption.

**R3 — Coût réel du double fencing.** Le renouvellement de propriété sur la même ligne, les validations `FOR KEY SHARE`, les takeovers `FOR UPDATE`, les acquisitions de slots et la discipline de verrous doivent être éprouvés en PostgreSQL. Envisager de simplifier seulement si la preuve de correction reste équivalente.

**R4 — Disponibilité temporelle.** Utiliser une sémantique d'horloge DB correctement choisie : les transactions courtes, `now()` figé au début d'une transaction et les attentes de verrous peuvent produire une vérification d'expiration périmée. À préciser avant code.

**R5 — Coût du plafond et des sélections.** Comptage de slots sous backlog, `partitionHash`/partition fixe, préfixes globaux sûrs, nombreux slots réclamés/différés, indexation et absence de famine à mesurer. Un simple LIMIT sans ordre ne prouve pas l'équité.

**R6 — Mixité WRITE/READ.** Vérifier que toute Consumption et son slot/checkpoint/propriété de partition sont dans une transaction physique cohérente et qu'un Worker mixte peut effectivement router les opérations vers ces autorités sans transaction distribuée. La même notion de partition ne doit pas impliquer implicitement une même ligne physique de propriété dans plusieurs bases.

**R7 — Critère de simplicité du slot.** Confirmer l'utilité de `partitionOwnerToken` (reprise immédiate), `sequencePosition` (sélection/diagnostic) et du plafond COUNT ; ne conserver ces optimisations que si leur utilité est mesurable. La suppression des états de complétion redondants est acquise conceptuellement, mais nécessite preuve.

**R8 — Sémantique des burnables.** Une famille burnable destinée à une seule Consumption exclut un rejeu du même consommable terminal ; c'est un contrat distinct de celui des événements. Le statut des tâches internes et leur multiplicité doivent être explicités.

## 7. Prochaines vérifications et décision de statut

1. Revue contradictoire des invariants R1–R8, surtout preuve de « découvert et sans slot ⇒ terminé » et perte/reprise de partitions.
2. Élaguer les champs et opérations qui n'apportent pas de garantie démontrable.
3. Tracer les scénarios E2E WRITE → événements → READ et WRITE → tâches → READ, avec frontières transactionnelles.
4. Définir les preuves V3-5 : interleavings PostgreSQL, crash/UNKNOWN, ressources, backlog, latence, WAL et contention.
5. Revoir V3-2/V3-3 pour les deux sources internes, puis actualiser le canon.

**Aucune implémentation V3 avant V3-CADRE. Le présent document est un cadrage consolidé, pas une certification.**
