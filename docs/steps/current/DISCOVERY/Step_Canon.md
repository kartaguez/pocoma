# DISCOVERY — Step Canon

Date de consolidation : 2026-10-20. Référence vérifiée : `feat/consumption-transaction-protocol`,
`8e09b433a949d8ecb06f048bdc0711edd1885e0d` (candidat 4E intégré à l'identique). **current_canon** du chantier séquencement → Discovery.
Le responsable du dépôt porte les arbitrages ; **4A–4E : implémentation fonctionnelle terminée**.
**4E FUNCTIONAL IMPLEMENTATION : CLOSED / PASS. Discovery efficiente : NEXT.**
**PRODUCTION ACTIVATION READINESS : NOT CERTIFIED. Activation production : NON AUTORISÉE.**
Le démarrage conserve PREPARED ; une base de développement ACTIVE exige une opération administrative explicite.

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
  l’autorité de séquencement, et non ExternalIdentity ni Expense.
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

## Séquencement E : implémentation fonctionnelle 4A–4E terminée

Arbitrage explicite du responsable, 2026-10-20 : dissocier la clôture fonctionnelle de 4E,
la qualification des performances après raccordement E2E et la certification d'activation.
La réserve « sous réserve de validation finale des gates de clôture » est levée par les trois
nouveaux gates PASS, consignés dans la [preuve de clôture](../../../testing/evidence/Event_Sequencing_Step_4E_Functional_Closure_2026-10-20.json).
Aucun invariant transactionnel ni test n'a été assoupli.

Le candidat `8e09b433` de `audit/step-4e-candidate`, parent documentaire `864fdec8`, est intégré
par avance rapide dans `feat/consumption-transaction-protocol` : exactement 23 fichiers,
20 empreintes du rapport et les 23 contenus/modes de l'archive concordants, aucun changement
fonctionnel supplémentaire. [Rapport 4E](../../../architecture/Event_Sequencing_Step_4E_2026-10-20.md)
et [runbook](../../../operations/event-sequencing-runbook.md) portent V31 administratif,
cutover/backfill atomique et reprise, quatre familles, observabilité et qualification locale.
Le correctif de résolution COMMIT incertaine conserve UNKNOWN après perte réelle d'accusé,
avec manager JPA et policies conservés ; l'idempotence métier exhaustive reste en réserve.

Clôture relancée : **V-E 1230, architecture 1388, full reactor 1388**, 0 failure/error,
un seul skip historique 3B par gate ; **35 cas 4E sans skip dans chaque gate**.
Les preuves initiales et leurs anciens statuts restent des instantanés historiques.
Les réserves de clôture **R1 COMMIT UNKNOWN, R2 portabilité V31 non-public, R3 performance,
R4 privilèges déployés, R5 cutover représentatif, R6 documentation** restent au
[plan courant](Step_Plan.md#réserves-de-production-r1r6).
Elles ne bloquent pas la Discovery ; elles conditionnent selon applicabilité la certification
de production. La frontière SQL R1 de l'ancien audit est suivie ici sous R4.
Aucune production activée ; aucune donnée de production disponible. Les futurs tests de
performance E2E utiliseront des jeux synthétiques dimensionnés ; la répétition propre à un
environnement déployé reste distincte.

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
producteur/owner réel n’était recâblé à la clôture 4B. À cette clôture historique 4B, les garanties ACTIVE restaient TARGET ; leurs preuves fonctionnelles hors production sont désormais apportées par 4E. La Discovery reste NEXT.

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

**CURRENT hors production 4A–4E : owner/registre/flush/fermeture, allocation E et positions qualifiés sur bases isolées ; activation déployée NOT CERTIFIED.** Quatre compteurs transactionnels E, un par famille, associations typées EventId/position,
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

**TARGET A1 — CONTRAT DE CONCEPTION RETENU LE 2026-10-21, NON IMPLÉMENTÉ.**
[Spécification détaillée A1](../../../architecture/Discovery_A1_Contract_2026-10-21.md).
Discovery et worker Consumption sont alignés **1:1**, mono-famille, par
`(eventFamily, consumptionId UUID, partitionId)`. `consumptionId` désigne un flux et une génération
immuable de règles opaques pour Discovery ; plusieurs générations peuvent être actives simultanément.
Un seul worker par partition ; exécution des slots initialement séquentielle. Discovery enrôle
**chaque Event** de sa famille appartenant à sa partition, sans routage métier. Les consumers
sont responsables de l'interprétation, des no-op, de l'idempotence et de la commutativité ;
Consumption seule gère Claim, retry et finalisation, sans attente de DONE par Discovery.

Le partitionnement s'appuie sur le **hash métier figé à l'écriture de chaque Event** : Pot pour
BusinessEvent, Command pour CommandTerminalEvent, identité externe pour BindingFact, UserId pour
UserCreatedFact (hash durable à ajouter pour ce dernier). Modulo/configuration fixés par flux,
changements via administration explicite, jamais reconfiguration silencieuse. Le filtre intervient
**après** lecture de la plage contiguë des positions E ; positions non locales incluses dans la
preuve de progression globale de famille.

Checkpoint durable par triplet, `last_examined_position`, représentant toutes les positions E
examinées et les slots locaux garantis. En une transaction PRIMARY : lecture de C, lot contigu,
création idempotente de slots, **CAS `C → N` conditionné par la valeur lue** ; CAS perdu ou erreur
= rollback intégral, puis relecture/reprise. Pas de `SELECT FOR UPDATE` anticipé, mais les
verrous internes PostgreSQL restent possibles. COMMIT UNKNOWN exige réconciliation durable.
Les slots PENDING/claims/leases servent de reprise ; aucune liste de trous ni dépendance à DONE.

**Limites vérifiables A2** : unicité existante des slots sur quatre colonnes dont deux JSONB,
absence actuelle de `consumption_id`/`partition_id` relationnels dans `consumption_slots`,
stabilité des hash et sélections de reprise à prouver. L'algorithme et les optimisations ne sont
pas livrés par le seul contrat A1. Activation production toujours NON AUTORISÉE.

## Maintenance

Le canon évolue lors d'une décision architecturale prise/révisée, avec statut, justification et preuve.
Le [plan courant](Step_Plan.md) évolue avec l'avancement ; le [plan suivant](Next_Plan.md) avec les
priorités/dépendances. Au changement de chantier, consolider l'acquis dans le canon et F/A/G, archiver
le Step clos avec sa traçabilité et promouvoir le prochain plan courant. Voir la
[gouvernance](../../../Documentation_Rationalization_Audit.md#15-gouvernance-documentaire).

### Décision A2.0 — provenance hors moteur générique (2026-10-21)

**RETENU, NON IMPLÉMENTÉ.** Le protocole générique Consumption ne persiste ni ne restitue systématiquement des `ConsumptionInput`/`ConsumptionResult`. Le Consumer est seul responsable des références causales et des effets métier qui lui sont réellement nécessaires. Le moteur conserve les slots, claims, CAS/fencing, retry et la finalisation atomique avec les effets ; les issues terminales SUCCESS/REJECTED/FAILED/ABANDONED et leurs raisons restent distinctes des Results métier. Les tables historiques `consumption_inputs` et `consumption_results` ne sont **pas supprimées** par cette décision : A2 doit inventorier leurs dépendances, décider de la rétention/migration et prouver la non-régression avant DDL ou suppression d'API.
