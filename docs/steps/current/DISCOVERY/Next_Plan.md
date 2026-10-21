# DISCOVERY — Next Plan : trajectoire jusqu'au READ

Date : 2026-10-20. **next_plan — TARGET de travail, non implémentée par cette consolidation.**
Référence applicative 4E : `8e09b433a949d8ecb06f048bdc0711edd1885e0d`, intégrée à la branche source ; [canon/statuts](Step_Canon.md) et [clôture fonctionnelle 4A–4E](Step_Plan.md).

## Dépendances et points de décision

Ordre de livraison : **4A–4E CLOSED / PASS fonctionnel hors production → A Discovery (NEXT) → B contrats READ → C matérialisation/consommation READ**. La certification de production est un gate distinct, toujours NOT CERTIFIED ; aucune activation production autorisée.

L'[audit de préparation 4D](../../../architecture/Event_Producer_Wiring_Step_4D_Readiness_2026-10-20.md)
au HEAD `4defd5a6` a déclaré V-D avant code. L’arbitrage UUID auxiliaire est approuvé et consigné
dans l’amendement daté 4A. Le [rapport de livraison 4D](../../../architecture/Event_Producer_Wiring_Step_4D_2026-10-20.md)
certifie désormais les producteurs réels et les deux compositions en PREPARED.

**4A–4E : implémentation fonctionnelle terminée, gates finaux PASS. Discovery efficiente : NEXT.**
La preuve 4E couvre les quatre familles sur bases isolées. Pour développer la Discovery,
utiliser une base de développement compatible, explicitement activée par l'outil administratif,
avec frontières historiques connues et writers homogènes. Aucun flip au démarrage.
Les réserves R1–R6 du [plan courant](Step_Plan.md#réserves-de-production-r1r6) restent ouvertes
selon applicabilité ; elles n'empêchent pas ce développement. Les mesures locales 4E sont
historiques et ne constituent pas une qualification de performance E2E ou de production.

L'inventaire et les arbitrages B peuvent commencer pendant A ; C dépend de contrats B stabilisés
et d'un chemin de découverte/reprise prouvé pour les consumers concernés. Aucun calendrier arbitraire.
D contient des évolutions fonctionnelles optionnelles, à découpler des prérequis A/B/C.
Chaque chantier sera découpé en lots vérifiables avant implémentation ; les gates V-D sont liés
au rapport 4D. Aucune cible Discovery/READ n’est déclarée livrée par sa présence dans ce plan.

**Mise à jour A1 (2026-10-21) :** [contrat de conception retenu](../../../architecture/Discovery_A1_Contract_2026-10-21.md),
non implémenté : Discovery et worker mono-famille alignés par
`(eventFamily, consumptionId, partitionId)`; partition par hash métier persisté à l'écriture,
scan de positions E globalement contigu, un slot par Event local et flux, checkpoint durable
atomique avec slots via CAS/rollback intégral. Aucune liste supplémentaire de réexamen nécessaire
au-delà des slots/claims existants. **Ces décisions prévalent sur les alternatives encore formulées
au conditionnel dans l'historique du chantier A ci-dessous.** A2 doit résoudre l'absence de
`consumption_id`/`partition_id` relationnels dans les slots, l'encodage canonique de leur clé,
le hash durable USER_CREATED et les fonctions de hash, puis fournir les preuves PostgreSQL.

## Chantier A — Discovery efficiente

**Entrée fonctionnelle hors production :** 4E intégré et prouvé sur les quatre sources, frontières historiques connues, positions immuables
indexées et writers homogènes. La lecture des seuls nouveaux Events ne remplace pas les reprises.
Les invariants du [canon](Step_Canon.md#discovery-efficiente--intention-et-hypothèses-bornées) s'appliquent.

1. **A1 — contrat de progression.** Définir checkpoint par famille/consumer/route et partition stable
   ou génération explicite. Un worker par partition est une hypothèse d'exploitation, pas une
   exclusion automatique de contenders après redémarrage. Verrou/CAS de progression et unicité des
   slots restent nécessaires. Ne pas confondre ordinal global de famille et version métier du Pot.
2. **A2 — enrôlement incrémental.** Seek par position, métadonnées de routage puis création idempotente
   de tous les slots requis ; checkpoint avancé atomiquement jusqu'à la dernière position entièrement
   examinée. Non-routage définitif sous catalogue identifié ou élément durablement à réexaminer,
   jamais oubli d'une inéligibilité temporaire. Plage non filtrée cohérente ou preuve de couverture
   des partitions : un résultat filtré vide ne prouve pas la continuité globale.
3. **A3 — reprise séparée.** Parcourir l'ensemble durable PENDING/due/reclaimable, y compris avant
   le checkpoint et les claims en cours. Busy est revisité à l'échéance utile, succès retire de la
   reprise seulement après finalisation confirmée. Conserver les transitions de retry/lease/takeover,
   le CAS et rollback existants. Découverte ne réserve ni n'autorise l'effet.
4. **A4 — indépendance et polling.** Nouveau/backlog sans famine, plusieurs travaux indépendants
   par worker, lot borné et backpressure. L'ordre de découverte ne crée pas un ordre d'exécution
   entre Pots, routes ou projections exactes. Si concurrence intra-worker retenue, chaque exécution
   possède sa transaction/son Claim ; pas de producteurs parallèles dans un même lot E.
5. **A5 — requêtes PostgreSQL.** Sélectionner d'abord la petite plage de positions et les slots dus,
   puis recharger par UUID/clé les seules sources nécessaires. Comparer jointure PK après seek et
   métadonnées relationnelles de routage minimales atomiques. Éviter les jointures JSONB sur tous
   les candidats historiques ; le JSONB des identités Consumption ne disparaît pas nécessairement.
   Examiner index checkpoint/position, UUID, consumer/état/échéance/lease et partition. DDL exact,
   index partiels/composites ou matérialisation minimale décidés à partir des plans réels.

**Garanties fonctionnelles à démontrer :** absence de perte sur crash/rollback/UNKNOWN ; pas de
checkpoint au-delà d'un travail non pris en charge ; nouveau consumer depuis zéro ; routes multiples,
ajout de route/version et backfill ; segmentation/repartitionnement ; doublon de découverte sans
double effet ; claim acquis non terminé repris après crash/expiration ; travail indépendant non bloqué
par un autre. Une hypothèse « un worker » n'exonère aucun cas de chevauchement.

**Objectifs d'optimisation, sans garantie actuelle :** moins de lignes/pages lues à nouveaux Events
et backlog égaux lorsque l'historique grandit ; moins d'anti-jointures JSONB candidates ; coût global
acceptable de checkpoint/slots/index/WAL. Capturer avant/après PostgreSQL avec mêmes données,
routes, concurrence et matériel : `EXPLAIN (ANALYZE, BUFFERS)`, rows/loops, buffers, temps/latences,
débit, attentes, backlog, taille/WAL. Seuils d'acceptation à fixer avant mesure ; pas de p95 inventé.

**Sortie A :** protocole et reprise durables certifiés, migration/checkpoint bootstrap et stratégie de
retour documentés, preuves de concurrence/crash. Préparer un benchmark reproductible et les plans de requêtes pour démontrer un parcours incrémental ; la campagne de performance représentative et les seuils sont qualifiés au gate E2E ci-dessous. Le coût du backlog doit dépendre des travaux restants plutôt que de toute l’histoire. Comparer à la
[Discovery actuelle](../../../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/processing/event/JdbcProjectionMaterializationDiscoveryAdapter.java)
et à son [test PostgreSQL](../../../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/processing/event/JdbcProjectionMaterializationDiscoveryAdapterPostgresTest.java).
RegistrationResult provient d'un outcome, pas d'une des quatre nouvelles familles : son optimisation
éventuelle exige un protocole propre, sans inventer un Event terminal Registration.

**Ouvert :** schéma exact et durabilité de la liste de réexamen, découpage checkpoint/route,
preuve de progression par partition filtrée, équité, tailles de lot, concurrence interne, stratégie
index/métadonnées et seuils. Les slots existants sont la première option, pas une nouvelle file obligatoire.

## Gate de qualification performance après raccordement E2E

Le gate suit le raccordement complet du parcours E2E retenu, y compris le premier cas de Discovery ; il peut être exécuté avant la refonte READ B/C si ce parcours utilise les contrats READ actuels. Il ne crée pas une dépendance obligatoire à cette refonte.

R3 reste ouvert : définir avec le responsable les seuils débit, p95/p99, backlog, attentes E,
arrêt et fenêtre avant la campagne ; documenter matériel, PostgreSQL, pool, routes et workers.
Aucune donnée de production disponible : jeux synthétiques reproductibles, volumes historiques
croissants, faible/nominal/pic définis, warmup et échantillons suffisants, répétitions et saturation.
Mesurer E2E et par étape, équité nouveau/reprise, plans/buffers/WAL et contention E ; conserver
la référence 4E pour attribuer le coût du séquencement et de la Discovery. Aucun gain acquis.
Cette campagne ne conditionne pas le démarrage de la Discovery ; elle conditionne la qualification
de capacité et, avec R1/R2/R4/R5/R6 selon applicabilité, la certification avant production.

## Chantier B — Refonte du modèle READ

**Entrée :** inventaire CURRENT ci-dessous, cas d'accès et règles d'autorisation explicites.
La cible discutée ici est un cadrage pour arbitrage ; ni nouveaux types, ni endpoints, ni stockage
ne sont livrés. Le [contrat exact existant](../../../architecture/read-side-target.md) continue à gouverner
AUTH/READ_POT/POT_BALANCES tant qu'une décision de remplacement n'est pas prise et migrée.
Le [snapshot post-PCL](../../../architecture/read-side-current-state.md) est historique ;
[Architecture CURRENT](../../../architecture/Architecture.md) décrit les chaînes actuelles.

| Projection/index CURRENT et preuve | Correspondance cible à examiner | Décision/migration restante |
|---|---|---|
| RegistrationResult immuable, direct depuis RegistrationOutcome, owner historique E ; [service](../../../../app/engine-materialize-registration-result/src/main/java/com/kartaguez/pocoma/engine/materialize/registrationresult/MaterializeRegistrationResultService.java) | `RegistrationResult` | Préserver identité/request et indépendance de CURRENT_BINDING ; examiner store/contrat sans réintroduire ProjectionTask obligatoire |
| CommandResult immuable, direct depuis CommandTerminalEvent/outcome ; [service](../../../../app/engine-materialize-command-result/src/main/java/com/kartaguez/pocoma/engine/materialize/commandresult/MaterializeCommandResultService.java) | `CommandResult` | Préserver unicité, comparaison et owner historique ; pas le vieux COMMAND_RESULT via Event→Task |
| CURRENT_BINDING convergent ATTACHED/DETACHED, révision monotone ; [adapter](../../../../app/infra-persistence-read-jdbc/src/main/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/JdbcCurrentBindingAdapter.java) | `CurrentBinding` mutable | Garder tombstone, divergence même révision et lecture READ E→U ; pas d'historique complet implicite dans la ligne courante |
| AUTH@V exact : créateur et relations shareholder/user ; [projector](../../../../app/projector-pot/src/main/java/com/kartaguez/pocoma/projector/pot/AuthProjector.java) | Autorisation du header et liste des pots accessibles | Examiner autorisation courante/historique et révocation ; aucun remplacement d'AUTH décidé par le seul header |
| READ_POT@V exact : POT + SHAREHOLDER + contenu complet EXPENSE ; [définition](../../../../app/domain-projection/src/main/java/com/kartaguez/pocoma/domain/projection/pot/definition/ReadPotProjectionDefinition.java), [projector](../../../../app/projector-pot/src/main/java/com/kartaguez/pocoma/projector/pot/ReadPotProjector.java) | `CurrentPotHeader` mutable : infos Pot, shareholders, références `(expenseId, expenseVersion)` ; projections des versions d'expense séparées | Scinder les données ; **le header n'embarque pas le contenu complet de toutes les expenses**. Définir la version exacte de l'expense, les suppressions et la disponibilité des références |
| POT_BALANCES@V exact, séparé ; [définition](../../../../app/domain-projection/src/main/java/com/kartaguez/pocoma/domain/projection/pot/definition/PotBalancesProjectionDefinition.java) | Projections balances conservées séparément | Déterminer clé/granularité, cohérence avec header/expenses et conservation historique ; aucun embedding imposé |
| LKV : max version Pot consommée, sans contrat de complétude ; [architecture](../../../architecture/Architecture.md#convergent-current-state-indexes) | Signal éventuel pour rattrapage | Ne pas sélectionner un header complet sur cette seule valeur ; utilité/retrait à arbitrer |
| Aucune liste de pots exposée dans le modèle fonctionnel CURRENT ; [fonctionnel](../../../product/Functional_Model.md) | Liste des pots accessibles à un utilisateur | Clé user/Pot, droits, ordering/pagination et invalidation à concevoir ; ne pas réactiver les anciennes tables/listings supprimés |

Travaux B : définir contrats d'accès, granularité/identités, états absent/non-prêt/failed, pagination,
règles de visibilité et tombstones. Décider si la lecture historique exacte Pot demeure telle quelle,
est recomposée ou évolue par contrat versionné ; aucune suppression implicite de cette capacité.
L'identifiant/version d'expense ne doit pas être déduit d'une position E : version Pot, version propre
d'expense et position de publication sont des notions à distinguer explicitement.

**Sortie B :** ADR/contrats validés, mapping complet CURRENT→cible (dont AUTH et LKV), règles
concurrentes et de compatibilité, critères observables et plan de migration. Questions : disponibilité
atomique header/références, conserver le dernier header complet ou exposer non-prêt, rétention des
versions immuables, autorisation historique versus courante, référence d'expense et suppression.

## Chantier C — Matérialisation et consommation READ

**Entrée :** contrats B figés et découverte/reprise prouvées pour chaque source concernée ; ne pas
forcer tous les matérialiseurs à passer par ProjectionTask. Les Results directs et index convergents
CURRENT sont des capacités réelles à préserver ou migrer explicitement.

| Données / granularité cible | Stratégie à spécifier et prouver |
|---|---|
| Results par command/request, versions d'expense et balances historiques par clé exacte retenue | Immuables : INSERT/compare ou contrat de publication exacte explicite, détection divergence, rejouabilité |
| CurrentBinding par ExternalIdentity, CurrentPotHeader par Pot, accès user/Pot | Mutables : version/revision monotone, idempotence à même rang, tombstones et rejet de divergence ; ne pas écraser par un Event plus ancien |
| Références du header vers expenses/version et balances séparées | Matérialiser les composants à la granularité utile ; définir complétude et visibilité lors de commits indépendants, sans supposer la cohérence instantanée |

Définir delta événementiel ou reconstruction à version exacte par projection ; un delta exige ordre
par clé, détection des sauts et replay des versions manquantes. Un snapshot indépendant peut autoriser
des sauts seulement si son contrat le prouve. L'ordre de positions E par famille n'est ni un ordre de
finalisation des workers ni un ordre commun entre familles. Les fences de consommation empêchent
le stale commit ; les stores doivent aussi protéger la monotonie/immutabilité des données.

Reconstruction : nouvelles identités/générations de consumer si contrats changent, backfill historique,
rattrapage concurrent du flux neuf, contrôle de parité, bascule des lecteurs après complétude et
retour documenté. Ne pas utiliser le max LKV comme preuve de readiness. Réparer un composant manquant
sans renuméroter les Events, ni effacer le travail des autres consumers.

Workers/polling : lots bornés et indépendants, priorité équitable nouveau/reprise, backpressure,
leases et reprises après crash ; coût mesuré des snapshots versus deltas. Réduire les lectures PRIMARY
répétées et l'amplification de réécriture : charger seulement la version d'expense requise, indexer
les références et matérialiser le minimum utilisé par les accès. Aucune jointure JSONB historique
massive déplacée silencieusement dans un autre worker. Aucun gain chiffré acquis.

Contrats d'accès : endpoints READ depuis READ/projections, identité/autorisation incluses, erreur
non-prêt distincte d'une divergence technique, pagination stable, clés exactes et versions exposées.
Les consommateurs ne reconstruisent pas librement à partir de WRITE au moment d'une lecture HTTP.

**Sortie C / preuves :** E2E admission→outcome→Result et Event→travail→header/expense/balances,
permissions et listing après attach/detach, retard d'un composant, doublons/désordre, crash/takeover,
reconstruction + rattrapage et parité avec références historiques. Tests store PostgreSQL et mesures
lignes/buffers/WAL/latence/volume sur gros Pots, nombreux users/Pots, backlog et accès à chaud/froid.
Seuils fixés avant qualification, sans transformer les mesures exploratoires 3B en capacité READ.

## Chantier D — Préparation des évolutions fonctionnelles

Ces sujets enrichissent le produit ; **aucun n'est un prérequis général obligatoire** à la refonte READ.
La [sémantique d'obsolescence livrée](../../../architecture/Pot_Command_Obsolescence_Implementation_2026-10-18.md)
reste l'autorité jusqu'à décision spécifique.

| Évolution | Dépendance éventuelle et séparation |
|---|---|
| `expectedVersion` amélioré | Clarifier contrat d'admission/exécution, version observée/attendue et erreurs ; impact READ seulement si nouvelles versions exposées nécessaires. Ne pas rouvrir 2B/2C par défaut |
| Poids par défaut des shareholders | Définir effet sur nouvelles expenses et historique avant projection ; pas de migration READ anticipée sans sémantique décidée |
| Extraction/versionnement propre de `shareholderWeight` | Pourrait séparer les conflits Details/Weights actuellement conservateurs ; demande modèle temporel/Events et migration propres, pas un préalable de CurrentPotHeader |
| Liste des pots | B peut préparer identité/pagination/autorisation ; livraison fonctionnelle lorsque index et accès C sont prêts, indépendamment de toutes les autres évolutions |
| Invitation, attach, detach | Définir états/permissions/ownership et impacts sur access list/header/CurrentBinding ; ne pas confondre invitation/shareholder et occurrence du Binding d'identité |

Avant chaque lot A/B/C/D, déclarer les slices réellement affectées selon la
[politique](../../../testing/Reactor_Verification_Policy.md). A vise EVENT et consommateurs modifiés,
B documentaire seul ne requiert pas Maven ; C vise PROJECTION/WEB et matérialiseurs réellement changés,
D dépend du comportement décidé. Ces indications ne remplacent pas l'analyse d'impact ni les gates
au HEAD du lot. Ce fichier évolue avec dépendances et priorités ; promotion dans le plan courant
seulement après consolidation/archivage du chantier achevé.
