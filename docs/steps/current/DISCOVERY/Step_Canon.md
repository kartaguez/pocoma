# DISCOVERY — Step Canon

Date de consolidation : 2026-10-20. Référence vérifiée : `feat/consumption-transaction-protocol`,
`84b67392b5b2db5e685f09a513770307475efc68` (livraison applicative 4D certifiée). **current_canon** du chantier séquencement → Discovery.
Le responsable du dépôt porte les arbitrages ; **4D CLOSED / PASS — 4E INCOMPLETE**. Runtime livré **PREPARED**, sans activation ni backfill.

## Autorité et lecture

Ce canon est le point d'entrée des décisions du chantier, sans remplacer les trois synthèses
CURRENT : [fonctionnel](../../../product/Functional_Model.md), [architecture](../../../architecture/Architecture.md),
[garanties](../../../guarantees/System_Guarantees.md). Le [Step Plan](Step_Plan.md) porte l'avancement,
le [Next Plan](Next_Plan.md) les dépendances Discovery → READ. Les spécifications détaillées restent
liées ci-dessous ; aucun échange extérieur au dépôt n'est nécessaire.

**CURRENT** signifie livré à la référence Git ; **TARGET retenue** signifie décision de conception
à réaliser ; **HYPOTHÈSE / OUVERT** ne vaut ni arbitrage définitif ni preuve d'implémentation.
Le nom « current_canon » n'accorde pas le statut CURRENT aux cibles qu'il recense.

## Consumption : décisions livrées et limites

- **CURRENT — autorités distinctes.** P est l'autorité/version globale du Pot, B l'autorité Binding
  WRITE et son occurrence exacte, S le slot Consumption. Pour Command, ordre utile **P → B → S**,
  puis clôture du Claim et commit. C'est un ordre partiel : Registration commence par B ; les
  matérialiseurs n'acquièrent pas arbitrairement P/B. Le terme E ci-dessous désigne exclusivement
  la future autorité de séquencement, et non ExternalIdentity ni Expense.
- **CURRENT — frontières physiques.** TA acquiert/commit le Claim ; TE exécute et finalise les effets ;
  TR est la recovery Binding après rollback TE ; TH traite l'échec après rollback confirmé.
  Le propriétaire ouvre/résout la transaction ; REQUIRED/MANDATORY participants partagent sa connexion.
  Une entrée publique propriétaire refuse une transaction/synchronisation ambiante non autorisée.
- **CURRENT — fencing.** Le prédicat final est `status=PENDING AND current_claim_id=C`, exactement
  une ligne ; clôture C exactement une ligne également. La lease autorise un takeover, son expiration
  ne retire pas seule l'autorité. `revision` n'est pas le token. Provenance et écritures JPA sont
  flushées avant CAS S ; aucune nouvelle action métier après CAS/clôture.
- **CURRENT — rollback/retry.** Perte de Claim, fence B refusé, CAS Pot perdu ou clôture refusée
  ne permettent aucun commit partiel. La reprise CAS de 2B reste technique dans le budget existant
  de quatre essais ; conflit Binding encore présent en TR reste technique, occurrence disparue
  permet REJECTED sans rejouer Pot. Les policies propres aux consommateurs restent distinctes.
- **CURRENT — résolution.** APPLIED exige COMMITTED confirmé ; ROLLED_BACK, COMMITTED et UNKNOWN
  restent distincts. COMMITTED/UNKNOWN n'autorisent ni FAILED automatique ni replay aveugle.
  Les doubles tentatives sont possibles ; les doubles effets durables perdants sont empêchés
  dans la transaction protégée, sous les contraintes/fences recensés. Aucun « exactly once » externe,
  absence universelle de deadlock ou délai de convergence n'est promis.

Justification et preuves : [protocole CURRENT](../../../architecture/consumption-transactional-execution.md),
[clôture WP6 et qualifications](../../../plans/2026-10-16-consumption-generic-transactional-protocol-wp6-closure.md),
[CAS 2B](../../../architecture/Pot_Command_Cas_Consumption_Step_2B_2026-10-18.md),
[fence partagé 2C](../../../architecture/Pot_Command_Binding_Shared_Fence_Integration_Step_2C_2026-10-18.md).
Les effets externes passent par un travail durable indépendant ; `runAfterCommit` ne garantit pas leur livraison.

## Versions métier et publication

**CURRENT.** La version globale du Pot ordonne ses états métier ; les versions temporelles des
sous-objets servent à l'obsolescence. Ce n'est pas une position globale de publication.
Les [règles d'obsolescence livrées](../../../architecture/Pot_Command_Obsolescence_Implementation_2026-10-18.md)
remplacent la recherche d'obsolescence dans les BusinessEvents. Ne pas réintroduire cette ancienne piste.

**CURRENT 4A — séparation métier/application/infrastructure.** Les Events typés Pot/Expense ne portent
ni EventId technique de publication ni position. Le métier produit via `EventPublisherPort` ; le
captureur d'invocation peut rester mémoire. L'orchestration demande ensuite la publication au port
spécialisé compatible. Pour la cible ordonnée, le contrat prescrit après acceptation éligible la déclaration explicite via
`PublishedEvents.recordPublishedEvent(EventRef)` ; `EventRef = (EventFamily, eventId UUID)`.
Ces types/contrats et **leur réalisation transactionnelle sont raccordés en 4D aux déclarants réels, en PREPARED**. Acceptation ≠ INSERT particulier ≠ commit ≠ visibilité externe.

Le [rapport 4A](../../../architecture/Event_Publication_Contracts_Step_4A_2026-10-19.md) définit les
enveloppes spécialisées, refus/no-op sans référence et tests de contrat. Gardes physiques locales
UserCreated, repository Binding y compris `new`, et outcome Registration sont livrées ; elles ne
certifiaient pas encore un owner E ou une phase OPEN en 4A ; les gardes OPEN et le poisoning des writers réels sont désormais certifiés par 4D.

| Famille durable obligatoire | Source PRIMARY | Portée |
|---|---|---|
| BUSINESS_EVENT | `business_event_outbox` | Pot et Expense, même famille ; Command et append auxiliaire |
| COMMAND_TERMINAL_EVENT | `command_terminal_events` | APPLIED/REJECTED/FAILED, TE/TR/TH selon chemin |
| BINDING_FACT | `external_identity_binding_facts` | lifecycle attach/detach et append auxiliaire |
| USER_CREATED_FACT | `user_created_facts` | initializer Registration gagnant |

RegistrationOutcome n'est pas une cinquième famille ni un RegistrationTerminalEvent.
La [matrice des producteurs et owners](../../../architecture/Event_Transactional_Sequencing_Step_4_Implementation_Plan_2026-10-19.md#5-carte-des-producteurs-et-portée-des-changements-futurs)
fait autorité pour la couverture détaillée (voir aussi §2.1 du même plan).

## Séquencement E : 4D PREPARED livré, qualification 4E en cours

Publication documentaire du 2026-10-20 : les résultats ci-dessous portent sur le candidat local
non committé. Le code, V31, les tests et les outils 4E ne sont pas livrés par ce commit documentaire.

**4E INCOMPLETE, qualification locale réalisée, non clos.** [Rapport 4E](../../../architecture/Event_Sequencing_Step_4E_2026-10-20.md)
et [runbook](../../../operations/event-sequencing-runbook.md) : DDL V31 administratif sans activation
au démarrage, cutover/backfill atomique sous gel et qualification PostgreSQL isolée.
La vraie perte d'accusé COMMIT a démontré une notification ROLLED_BACK de Spring/Hibernate avec
commit durable ; le runner PRIMARY signale maintenant cette résolution réseau incertaine au
wrapper Consumption, qui conserve UNKNOWN. Ce défaut est documenté avec sa preuve et la révision
d'impact préalable ; les invariants, le manager standard et les retries restent conservés.
Seuils métier, copie/version déployées et autorisation restent à qualifier ; aucun ACTIVE déployé.
V-E, architecture globale et full reactor passent ; les seuils et la qualification déployée restent ouverts, sans clôture 4E.

**Audit de préparation 4D au HEAD `4defd5a6` :** [inventaire, écarts et matrice A/B/C](../../../architecture/Event_Producer_Wiring_Step_4D_Readiness_2026-10-20.md).
L'arbitrage du 2026-10-20 a approuvé le retour UUID du port Business auxiliaire, puis la construction
et déclaration EventRef par sa façade applicative, sans dépendance Maven nouvelle. La cible 4A
conserve son historique et porte cet amendement daté. Le [rapport 4D](../../../architecture/Event_Producer_Wiring_Step_4D_2026-10-20.md)
et ses preuves clôturent V-D : writers réels gardés/poisonnés, même transaction PRIMARY, acceptations
exactement déclarées, deux compositions, trois gates et retours ancien runtime répétés.
**PREPARED** laisse E/positions inactifs ; ACTIVE a été testé exclusivement sur bases jetables.

**CURRENT borné, hors production — 4B CLOSED / PASS confirmé par audit indépendant statique.** Le [runner/collecteur PRIMARY et ses preuves](../../../architecture/Event_Transaction_Protocol_Step_4B_2026-10-20.md)
étaient livrés en 4B sans scan/configuration productive ; leur composition explicite est désormais raccordée en 4D. Propriétaire/participants, flush terminal,
snapshot fermé, poisoning, cleanup et résolutions CURRENT sont vérifiés dans COMMAND avec le
manager JPA standard. Le suffixe 4B est synthétique et limité aux tests ; aucune E active, aucun
producteur/owner réel n’était recâblé à la clôture 4B. Les garanties ACTIVE et l'optimisation Discovery restent TARGET.

L'audit favorable 4B porte sur `5640220b94f6208aa4c156e248d95e2eced1522f` et les preuves 4B déjà réalisées ;
l'auditeur n'a réexécuté ni Maven ni PostgreSQL. Il ne prouve pas une interception universelle
des erreurs SQL des writers réels par le runner 4B. Ses deux réserves non bloquantes pour 4B
sont couvertes par la certification **4D/V-D** : poisoning irréversible de chaque writer durable réel,
y compris JDBC, et identité physique JPA/JDBC sous le même manager PRIMARY pour tous les owners.
Leurs critères sont enregistrés une seule fois dans la [spécification, §6/4D, conditions A et B](../../../architecture/Event_Transactional_Sequencing_Step_4_Implementation_Plan_2026-10-19.md#4d--raccordement-complet-production-prepared),
suivis par le [plan, §4D](Step_Plan.md#4d--producteurs-et-orchestrations-réels-prepared-complet).
Aucune dette technique 4B n'est ouverte ; le périmètre et la clôture 4C restent inchangés.

**CURRENT borné — 4C CLOSED / PASS.** [V30 et suffixe JDBC dormant](../../../architecture/Event_Transactional_Sequencer_Step_4C_2026-10-20.md)
sont livrés : autorités/associations typées/FK/vue, PREPARED/version unique, droits et contrôles
ACTIVE différés ; blocs denses et ordre déclaré prouvés sur les quatre vraies sources en bases
isolées. COMMAND + BINDING : 1 091 tests, aucune failure/error, seul skip 3B préexistant ; upgrade
V29→V30 et deux retours au runtime initial verts. À la clôture 4C, aucun vrai owner/writer n’était raccordé, aucun compteur
ou position officielle attribué en production. Le schéma additif reste compatible CURRENT en
PREPARED ; compositions du dépôt certifiées en 4D, contrôle des comptes déployés avant cutover et activation/historique en 4E.
Les sondes de plans indexés ne sont pas des seuils de performance. Global/full non requis en 4C.

L'[audit indépendant 4C](../../../architecture/Event_Transactional_Sequencer_Step_4C_2026-10-20.md#clôture-documentaire-après-audit-indépendant)
confirme un **PASS statique**, sans réexécution Maven/PostgreSQL ni extension de certification.
R1 précise la frontière de confiance de la densité ; R2 et R3 renvoient respectivement aux conditions
B et A existantes de 4D. Leur suivi reste au §4D du plan et de la spécification, sans réouverture
ni dette nouvelle propre à 4C. Le raccordement ultérieur des producteurs réels est certifié en 4D, sans activation.

**Pourquoi.** Un timestamp/UUID ou une séquence PostgreSQL non transactionnelle ne prouve pas
l'absence d'un commit tardif sous un curseur déjà avancé. Une position dense committée par famille
permet de prouver une plage entièrement visible. Elle ne prouve pas que les conséquences sont
enrôlées, ni que leur consommation est terminée.

**CURRENT 4D : owner/registre/flush/fermeture PREPARED ; TARGET 4E : allocation E et positions en environnement déployé.** Quatre compteurs transactionnels E, un par famille, associations typées EventId/position,
allocation par blocs dans la même transaction que l'acceptation durable et les effets protégés.
Après finalisation S/Claim : flush complet, fermeture du lot, puis suffixe E dans le callback du
`TransactionTemplate`, avant commit physique par le gestionnaire JPA standard. Aucun DML métier,
retour à P/B/S ou publication tardive sous E ; pas de retry local, commit autonome ou rollback partiel.
L'orchestration est le seul déclarant ; aucun collecteur caché dans l'adaptateur/repository.

L'ordre d'acquisition E est fixe BUSINESS_EVENT → COMMAND_TERMINAL_EVENT → BINDING_FACT → USER_CREATED_FACT ;
seules les familles non vides sont acquises, verrous conservés jusqu'à résolution. À l'intérieur
d'une famille, l'ordre des appels/listes acceptés est conservé, sans tri UUID des nouveaux lots.
C'est un ordre global **par famille**, pas un ordre métier commun aux quatre familles ni aux travaux.
Rollback restitue le bloc ; lot vide ne touche pas E. Les positions sont positives, uniques, sans trou
sur l'ensemble conservé après cutover complet ; aucun compteur actif de production au HEAD de référence.

La [spécification Étape 4, §§3–7](../../../architecture/Event_Transactional_Sequencing_Step_4_Implementation_Plan_2026-10-19.md)
fixe fermeture/poisoning, droits, contraintes différées de complétude, PREPARED/ACTIVE et backfill.
Le tri UUID historique est reproductible, sans reconstruction de causalité ancienne. Les expériences
[3B](../../../architecture/Event_Terminal_Sequencing_Step_3B_PostgreSQL_Proof_2026-10-19.md) et
[3C](../../../architecture/Event_Terminal_Sequencing_Step_3C_Transactional_Integration_2026-10-19.md)
prouvent des scénarios isolés ; leurs mécanismes expérimentaux (tri UUID, gestionnaire spécialisé)
ne remplacent pas les choix finaux du plan 4. Le coût de contention E reste à qualifier.

**Publication indépendante non persistante.** Elle peut employer le même publisher métier sans
`PublishedEvents` ni E. Elle ne promet ni durabilité ni atomicité réseau/PRIMARY. Toutes les lignes
des quatre sources durables restent couvertes en ACTIVE : aucun opt-out déguisé en publication non ordonnée.

## Discovery efficiente : intention et hypothèses bornées

**CURRENT.** La Discovery Event lit déjà les métadonnées, sans payload, avec curseur local
`(recorded_at,event_id,projection_type)` et anti-jointure JSONB contre les slots DONE.
[Source JDBC](../../../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/processing/event/JdbcProjectionMaterializationDiscoveryAdapter.java).
Il n'existe pas de checkpoint durable par famille/consumer. Candidat découvert ≠ Claim acquis.

**TARGET de travail, conditionnelle.** Réduire les lignes PostgreSQL lues et les jointures JSONB
sur l'histoire en séparant (1) parcours incrémental des nouvelles positions, (2) enrôlement durable,
(3) acquisition effective, (4) exécution/finalisation indépendante. Les
[études Discovery, F.1–F.3](../../../architecture/Mutation_Authorities_Ordered_Publication_Consolidated_Study_2026-10-18.md#f1-code-actuel)
et la [numérotation, §10](../../../architecture/Event_Late_Transactional_Numbering_Study_2026-10-18.md#10-conséquences-pour-discovery-sans-changement-actuel)
sont des analyses conditionnelles ; le [chantier A](Next_Plan.md#chantier-a--discovery-efficiente) en organise la preuve.

Invariants exigés de toute future solution :

1. Curseur par famille/identité de consommateur, route et génération de partitionnement si nécessaires.
   Les positions strictement consécutives prouvent la continuité **globale de famille** ; un filtre de
   partition peut créer des écarts locaux et exige une preuve de balayage, pas `max(position)` des candidats.
2. N'avancer que jusqu'à une plage entièrement examinée et durablement prise en charge : tous les slots
   requis ou un non-routage définitif prouvé pour la configuration considérée. Enrôlement et curseur
   atomiques ; éligibilité temporaire conservée dans l'ensemble de reprise, jamais abandonnée.
3. Liste complémentaire durable de travaux à reprendre/réexaminer, prioritairement les slots PENDING,
   `nextClaimAt` et leases existants ; nouvelle table seulement si un besoin non représentable est prouvé.
   Les acquis en cours restent suivis sous le curseur : Busy n'est ni DONE ni motif de suppression.
4. Ne pas attendre la terminaison d'un travail pour découvrir les suivants indépendants. Un Event peut
   alimenter plusieurs routes/consumers ; aucun état consumed partagé. Acquisition et CAS/fencing restent
   les autorités, même si deux Discoverers rendent le même candidat.

**HYPOTHÈSE de dimensionnement : un worker par partition.** Un worker peut découvrir et traiter
plusieurs travaux indépendants ; taille de lot et exécution séquentielle/parallèle restent ouvertes.
Cette hypothèse ne remplace pas verrou/CAS du checkpoint pour chevauchement de redémarrage, ni les
claims. Crash, rollback, commit UNKNOWN, ajout de route et changement de partitions doivent être prouvés.
La requête exacte, le schéma du checkpoint, les index, l'équité nouveau/backlog et les seuils de gain
restent **OUVERTS**. 4E ne livre aucune de ces optimisations.

## Maintenance

Le canon évolue lors d'une décision architecturale prise/révisée, avec statut, justification et preuve.
Le [plan courant](Step_Plan.md) évolue avec l'avancement ; le [plan suivant](Next_Plan.md) avec les
priorités/dépendances. Au changement de chantier, consolider l'acquis dans le canon et F/A/G, archiver
le Step clos avec sa traçabilité et promouvoir le prochain plan courant. Voir la
[gouvernance](../../../Documentation_Rationalization_Audit.md#15-gouvernance-documentaire).
