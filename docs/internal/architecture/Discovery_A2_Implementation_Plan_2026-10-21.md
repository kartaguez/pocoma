# Discovery A2-P — Plan d'implémentation pour revue indépendante

Date : 2026-10-21. **PROPOSÉ — EN ATTENTE DE VALIDATION. Aucune implémentation A2 commencée.**
Branche auditée : `feat/consumption-transaction-protocol` ; HEAD de conception :
`95475772e9a1abda3fc738b0005468a62c442c3e`.

## 1. Autorité, méthode et limites de preuve

Ce plan applique le [canon](../steps/current/DISCOVERY/Step_Canon.md), le
[Step Plan](../steps/current/DISCOVERY/Step_Plan.md), le [Next Plan](../steps/current/DISCOVERY/Next_Plan.md),
le [contrat A1](Discovery_A1_Contract_2026-10-21.md) et le
[diagnostic A2.0](Discovery_A2_0_Consumption_Model_Simplification_2026-10-21.md).
La clarification « parenthèse simplification refermée » du Step Plan et la mission A2-P
reportent la refonte générale de Consumption **après Discovery**. Les mentions antérieures
« NEXT A2 : supprimer la provenance » dans A2.0/canon sont des cibles différées, pas une
permission de les implémenter dans A2. Aucun de ces documents n'est réécrit ici.

Lecture statique du code et des migrations versionnés, recherches `rg`, lecture des POM et
contrôle Git uniquement. Les dix commits distants A1/A2.0 ont été récupérés puis intégrés par
avance rapide de `2506368c` à la référence ci-dessus ; ils ne modifient aucun fichier `app/`.
Le worktree principal `feat/runtime-monolith` et les fichiers préexistants non suivis du
worktree de référence sont hors livraison. Le rapport non suivi `Architecture_Last_Skip_Audit_2026-10-20.md`
n'est pas une source normative de ce plan.

**CURRENT vérifié** ci-dessous signifie « présent dans les sources à ce SHA », non « observé
dans une base déployée ». Les preuves 4E citées restent des preuves historiques publiées dans
[le rapport 4E](Event_Sequencing_Step_4E_2026-10-20.md) et son
[registre de clôture](../testing/evidence/Event_Sequencing_Step_4E_Functional_Closure_2026-10-20.json).
Aucun Maven, PostgreSQL, DDL, test de concurrence ou benchmark n'est exécuté pour A2-P.
Tout SQL de ce document est **envisagé, non exécuté et non certifié**.

La [politique de vérification](../testing/Reactor_Verification_Policy.md) a été lue avant
l'analyse de `app/`. Pour **A2-P documentaire** : slice primaire NONE, secondaires NONE,
impact production interdit ; DB NONE ; architecture globale NOT REQUIRED ; full reactor
NOT REQUIRED. La déclaration prospective d'A2 est au §8 ; elle ne constitue pas une preuve.

## 2. État CURRENT vérifié

### 2.1 Quatre sources, identités, associations et index

Les références Sxx du §11 donnent chemins, lignes et symboles contrôlables au SHA d'audit.
Les noms d'index ci-dessous viennent du DDL, pas d'un catalogue PostgreSQL interrogé.

| Famille E | Source, clé de partition et contraintes CURRENT | Association E et accès CURRENT |
|---|---|---|
| BUSINESS_EVENT | `business_event_outbox.id` PK UUID ; `pot_id` propriétaire, `aggregate_id` parfois Expense, `version`, `pot_partition_hash` NOT NULL. `payload_json` n'est pas nécessaire au routage. V2 + contrat de types V15. Index `idx_business_event_outbox_claimable(status,lease_until,pot_partition_hash,created_at)` et `idx_business_event_outbox_pot_version(pot_id,version)`. Les colonnes lifecycle legacy ne sont pas une autorité de Consumption. [S01], [S02], [S14] | `business_event_positions(position PK CHECK >0, event_id UNIQUE NOT NULL FK → business_event_outbox.id ON DELETE RESTRICT)`. B-tree PK pour seek, unique UUID pour lookup inverse ; source rejointe par sa PK. [S09] |
| COMMAND_TERMINAL_EVENT | `command_terminal_events.event_id` PK ; `command_id` UNIQUE/FK → `command_outcomes.command_id`, lui-même FK → `recorded_commands`. `command_partition_hash` NOT NULL ; APPLIED/REJECTED/FAILED. Index `idx_command_terminal_events_discovery(command_partition_hash,recorded_at,event_id)`. Partition par Command même sans Pot. [S03], [S16] | `command_terminal_event_positions` : même forme PK position/unique UUID/FK restrict → source `event_id`. [S09] |
| BINDING_FACT | `external_identity_binding_facts.event_id` PK ; identité `(issuer,subject)` ; révision unique `(issuer,subject,binding_revision)`. `partition_hash` NOT NULL. FK stream, User et, après V22, occurrence exacte `(binding_id,issuer,subject,user_id)` ; V22 répare les facts/baselines, V23 ferme le contrat `fact_type`. Index `idx_external_identity_binding_facts_discovery(partition_hash,issuer,subject,binding_revision)`. [S04], [S05], [S06], [S17] | `binding_fact_positions` : PK position, unique UUID, FK restrict → source `event_id`. ATTACHED, DETACHED et historiques réparés appartiennent à cette famille. [S09] |
| USER_CREATED_FACT | `user_created_facts.event_id` PK ; `request_id` UNIQUE/FK → `registration_requests`, `user_id` UNIQUE/FK → `users`, `recorded_at` ; trigger refusant UPDATE/DELETE. **Aucun hash/partition persisté** ; seuls PK et index des deux uniques. Append gagnant de Registration, aucun consumer dédié CURRENT repéré. RegistrationOutcome n'est pas une cinquième famille. [S07], [S18] | `user_created_fact_positions` : PK position, unique UUID, FK restrict → source `event_id`. [S09] |

V30 crée aussi `event_sequence_authorities(family PK,last_position >=0)` avec quatre lignes,
`event_sequencing_protocol(singleton PK,protocol_version=1,mode PREPARED/ACTIVE)` et la vue
`event_position(family,event_id,position)` par UNION ALL des quatre associations.
Chaque association interdit UPDATE/DELETE par trigger ; les quatre sources ont un contrôle de
complétude **différé au commit en ACTIVE**. Les FK source sont immédiates. Ces objets ne stockent
aucun hash. La vue n'a pas d'index propre ; préférer les associations typées dans les requêtes A2.
La densité dépend du séquenceur, de ses droits et des writers conformes : une PK positive ne
suffit pas à prouver l'absence de trous. [S09], [S10], [S30]

### 2.2 Hash réellement utilisés : ne pas les uniformiser implicitement

| Chemin réel | Calcul à l'écriture / lecture | Conséquence A2 |
|---|---|---|
| Business outbox | `JpaBusinessEventOutboxEntity` appelle `PotPartitioner.partitionHash(UUID)` : `UUID.hashCode() & Integer.MAX_VALUE`. `segmentOf` applique `Math.floorMod`. [S14], [S15] | Lire le hash persisté du Pot, **pas** le hash d'Expense ou d'EventId. Le masquage du bit de signe fait partie des données existantes. |
| Command terminal | `JdbcCommandOutcomeAdapter.publish` persiste `outcome.commandId().value().hashCode()` signé. `JdbcCommandResultSource.next` normalise par double modulo. [S16], [S21] | Conserver le signé ; pas de remplacement par `hashtext(command_id::text)`. |
| Binding facts | `ExternalIdentityBindingFactJdbcRepository.APPEND` : `hashtext(jsonb_build_array(issuer,subject)::text)`, signé PostgreSQL. V22 utilise la même expression pour l'historique. [S05], [S17] | Ne pas recalculer en Java ni concaténer librement issuer/subject. Le hash déjà stocké reste la référence. Pas de garantie inter-version PostgreSQL inventée pour les futurs calculs `hashtext` ; contrôler lors d'un upgrade. |
| USER_CREATED | `JdbcUserCreatedFactAdapter.append` insère seulement les quatre colonnes existantes. [S18] | Ajouter un hash UserId à l'écriture ET pour l'historique, §4.3. |
| LKV CURRENT | `PartitionHash.forPot` renvoie `UUID.hashCode()` **sans masque** ; `WorkerSegment.owns` fait `floorMod`. L'adapter filtre en Java après pages de 128 candidates. [S19], [S23] | Écart réel avec le hash outbox quand le bit de signe est posé ; pour un nombre de partitions quelconque, les affectations peuvent différer. Nouveau flux = hash persisté A1 ; aucun hot-switch de l'ancien worker LKV dans A2. |
| Non-E, pour compatibilité | RegistrationRequest/Result : `hashtext(request_id::text)` calculé en lecture. ProjectionTask : `List<String>.hashCode()` sur `(projectionType,targetObjectType,targetObjectId,targetVersion)`, persisté dans `partition_hash`. `PartitionHash.forPipelinePot` contient encore `31*pipelineId.hashCode()+potId.hashCode()` ; ce helper n'est pas le writer outbox actuel. [S19], [S27], [S28], [S29] | Ne migrer aucun de ces hash vers E ; ne pas confondre présence d'un helper et usage réel. |

Normalisation proposée pour tous les hash persistés signés :
`mod(mod(hash::bigint, N::bigint) + N::bigint, N::bigint)` = `floorMod(hash,N)` pour `N>0`.
Le cast bigint **avant** l'addition évite l'overflow int de `remainder+N` ; ne pas utiliser
`abs(hash)`, notamment pour `-2147483648`. Java UUID.hashCode XOR les quatre mots de 32 bits
du UUID ; ce contrat déterministe ne se confond pas avec `UUID.toString().hashCode()`.
Collisions de hash autorisées : elles affectent seulement la répartition, jamais l'identité.
V30 interdit de modifier les **associations** E ; il ne rend pas tous les champs des quatre
sources immuables par ce seul mécanisme. Les writers inspectés fixent les trois hash existants
à l'INSERT et ne les réaffectent pas. La protection contre un UPDATE SQL administratif abusif
reste dans la frontière de confiance des droits/writers, notamment réserve R4 ; ne pas affirmer
une stabilité garantie contre tout SQL privilégié.

### 2.3 Slots, claims et provenance CURRENT

V4 et V6/V7 sont les seules évolutions de ce lifecycle recensées. [S08], [S11], [S12]

- `consumption_slots` : `slot_id UUID PK`, quatre colonnes d'identité NOT NULL,
  `revision BIGINT >=0 DEFAULT 0`, `last_attempt_number INTEGER >=0 DEFAULT 0`, status,
  outcome/raison, claim courant, `next_claim_at`, `created_at`, `done_at`.
- `uk_consumption_slots_key` UNIQUE
  `(consumable_type,consumable_components JSONB,consumer_type,consumer_components JSONB)`.
  Consumable = array non vide ; consumer = array pouvant être vide. **Ni consommation UUID,
  ni famille E, ni position E, ni partition relationnelle dédiée**.
- `idx_consumption_slots_status_next_claim_at(status,next_claim_at)` est l'unique index secondaire
  explicite de slots. Il ne sélectionne pas directement un flux/une partition.
- Contraintes : PENDING ⇒ pas d'outcome/raison/done ; DONE ⇒ outcome, done, aucun claim courant ;
  SUCCESS sans raison, REJECTED/FAILED/ABANDONED avec raison non vide ; échéances/done ≥ création.
- Claims : PK claim UUID, uniques `(slot_id,attempt_number)` et `(slot_id,claim_id)`, FK slot,
  lease > claimed_at, fermeture exclusive ended/invalidated, failure complète et reason cohérente.
  FK cyclique du slot `(slot_id,current_claim_id)` vers claim ; conserver ces contraintes.
- `consumption_inputs` : PK input UUID, UNIQUE `(slot_id,subject_type,subject_id,subject_version)`,
  FK slot et version ≥1. `consumption_results` : PK result UUID, FK slot, shape/version contrôlées,
  index `(slot_id,created_at,result_id)`. Ports, adapter et appels du finalizer existent encore. [S33], [S34]
  A2.0 n'a pas supprimé ces données ni ces API.

`JpaConsumptionSlotRepository.insertInitial` insère PENDING et `ON CONFLICT` sur les quatre colonnes
fait DO NOTHING. `JpaConsumptionLifecycleAdapter.acquire` appelle cette insertion puis
`findByKeyForUpdate`, traite DONE/NotReady/Busy, invalide un claim expiré, crée/flush le nouveau claim
et l'installe. Finalisation et retry visent `slot_id` avec
`status='PENDING' AND current_claim_id=:claimId`, exigent exactement une ligne et closent exactement
un claim. `revision` incrémentée n'est pas un fence token. Le flush automatique précède le CAS.
A2 doit produire **la même ConsumptionKey** côté Discovery et acquisition, pas un slot parallèle. [S31], [S32]

### 2.4 Discovery, services et adaptateurs CURRENT

| Parcours | Ports / supra / adaptateur et critères réellement lus |
|---|---|
| Business → Task | `ProjectionMaterializationDiscoveryPort.findCandidates` → `ProjectionMaterializationConsumptionSource` (cursor local) ; JDBC `JdbcProjectionMaterializationDiscoveryAdapter` : routes EventType→ProjectionType, métadonnées, anti-jointure slots DONE JSONB, ordre `(recorded_at,event_id,projection_type)`. `ProjectionMaterializationConsumptionKeys` = `EVENT[eventId] / PROJECTION_TASK_MATERIALIZER[projectionType]` ; `ProjectionMaterializationConsumptionService` → `ProduceProjectionTaskService.ensure` dans finalisation fenced. [S20], [S24], [S25] |
| Business → LKV | `LatestKnownVersionEventDiscoveryPort` → `JpaLatestKnownVersionEventDiscoveryAdapter`/repository ; ordre version métier/date/UUID, jointure slots/claims due ; filtrage Java par hash recalculé. `LatestKnownVersionConsumptionLocator` = `EVENT[eventId] / SOURCE_VERSION_WATERMARK[]`, reload puis advance. [S23] |
| Command terminal → Result | `CommandResultDiscovery.next/reload`, `JdbcCommandResultSource`, `CommandResultConsumptionLocator` ; ordre date/EventId, filtre partition, left join slots/claims ; `COMMAND_TERMINAL_EVENT[eventId] / COMMAND_RESULT_MATERIALIZER_V2[]`. Reload outcome et requester dans l'exécution. [S21], [S26] |
| Binding → CURRENT_BINDING | `BindingFactDiscoveryPort.findNextEligible`, `JdbcBindingFactDiscoveryAdapter`, `BindingFactConsumptionLocator` ; ordre issuer/subject/révision, filtre partition, left join slots/claims due ; `IDENTITY_BINDING_FACT[eventId] / CURRENT_BINDING_PROJECTOR[]`. Reload par EventId. [S22] |
| UserCreated | Writer `UserCreatedFactPort`/`JdbcUserCreatedFactAdapter`, mais aucun port/locator de consommation UserCreated CURRENT. [S18] |
| Coordination commune | `AcquireConsumptionService`, `ExecuteConsumptionService`, `FinalizeConsumptionService`, `HandleConsumptionFailureService` ; wrappers `Transactional*ConsumptionUseCase` ; `SequentialConsumptionOrchestrator`, `AcquireThenFinalizeConsumptionOrchestrator`, `ConsumptionPollingWorker`. Les sources sont candidates, pas des autorisations d'effet. [S32], [S33], [S35] |

Aucun de ces discoverers n'utilise les associations E ni un checkpoint durable.
A2 ne remplace pas leurs politiques métier par un filtre Discovery : le nouveau chemin enrôle
chaque Event local ; filtrage/no-op/fan-out éventuel appartient à l'exécution de la génération.

### 2.5 Transactions PRIMARY, CAS et fencing

[S30], [S31], [S32], [S33], [S35], [S36] et le
[protocole transactionnel CURRENT](consumption-transactional-execution.md) distinguent :

- TA : acquisition/commit Claim ; TE : effets, provenance actuelle, flush, CAS S, clôture C, commit ;
  TR : recovery Binding après rollback TE ; TH : failure handler après rollback confirmé.
- `ConsumptionOwnerTransactionRunner` refuse transaction/synchronisation ambiante et distingue
  COMMITTED/ROLLED_BACK/UNKNOWN ; participants REQUIRED/MANDATORY partagent la connexion PRIMARY.
- `PrimaryEventTransactionRunner` possède le batch OPEN/CLOSED/POISONED/CLEANED ; flush terminal,
  fermeture puis `JdbcEventTransactionSequencer` avant commit JPA. Ordre E fixe Business → Command
  terminal → Binding → UserCreated ; bloc transactionnel via `reserve_event_positions`.
  Préfixe Command P→B→S ; Registration commence par B. Pas de retour métier sous E.
- `PrimaryEventProtocol` vérifie même manager JPA/EMF/datasource ; le démarrage vérifie seulement
  mode/version, sans activation. Les templates CURRENT ne fixent pas universellement l'isolation :
  ils héritent du manager/serveur ; des tests du dépôt constatent READ COMMITTED. Ne pas extrapoler
  à un déploiement. Les loaders de snapshots projection utilisent parfois REPEATABLE_READ, hors A2.
- Transport COMMIT ambigu après callback achevé : marqueur `TransactionCommitUnknownException`,
  UNKNOWN conservé malgré une notification framework ROLLED_BACK possible. Reprendre ce principe
  pour l'owner A2 ; ne pas réutiliser un wrapper perdant ce marqueur.

### 2.6 Consommateurs non-E des mêmes tables

| Source durable non-E | Identité CURRENT | Fichiers/risque à préserver |
|---|---|---|
| RecordedCommand | `COMMAND[commandId] / COMMAND_PROCESSOR[]` | `CommandConsumptionKeys`, locator, `JpaCommandConsumptionDiscoveryAdapter`/repository ; écrivent aussi outcomes et familles E mais **leur entrée est non-E**. V24 contient des contrôles de slots d'anciens envelopes. [S29] |
| ProjectionTask canonique V13 | `PROJECTION_TASK[projectionType,targetObjectType,targetObjectId,targetVersion] / PROJECTION_EXECUTOR[projectionType]` | `ProjectionTaskKeys`, `JdbcProjectionTaskStoreAdapter`, `ProjectionTaskConsumptionService`, runtime Task ; clé et hash propres. [S13], [S28] |
| RegistrationRequest | `REGISTRATION_REQUEST[requestId] / REGISTRATION_WORKER_V1[]` | `RegistrationConsumptionLocator`, `RegistrationDiscoveryPort`, `JdbcRegistrationDiscovery` ; exécute le producteur UserCreated. [S27] |
| RegistrationOutcome → RegistrationResult | `REGISTRATION_OUTCOME[requestId] / REGISTRATION_RESULT_MATERIALIZER_V1[]` | `RegistrationResultConsumptionLocator`, `RegistrationResultDiscovery`, `JdbcRegistrationResultDiscovery` ; outcome direct, pas Event terminal Registration. [S27] |

La provenance demeure écrite/lue par les spécialisations et leurs tests ; `FinalizeConsumptionService`
la persiste avant le terminal CAS, `JpaConsumptionProvenanceAdapter` l'expose. Les tests LKV, Command,
Binding et Results l'observent. Les scripts Event preflight/validate, migrations V6/V24 et probes SQL
emploient les identités/outcomes actuels. **Pas de conclusion « inutilisé » et pas d'audit de suppression
requis pour livrer A2** : la suppression est explicitement hors scope. [S11], [S29], [S33], [S34], [S38]

### 2.7 Migrations et administration existantes

Chaîne PRIMARY présente **V1–V31**. Pour le domaine audité : V2 outbox, V4 slots, V6 raison,
V7 failure code, V13 tasks canoniques, V15 EventTypes, V17 terminal Command,
V20–V23 Binding, V24 Command envelope, V25/26 CommandResult, V27–V29 Registration,
V30 associations E/PREPARED, V31 administration. V5 est historique ; V16 supprime ses structures legacy.
Ne modifier aucune migration historique.

V31 crée `event_sequencing_cutovers(operation_id,...)`, `inspect_event_sequencing()`,
`audit_event_sequencing_role(name)`, `activate_event_sequencing(uuid,name)` ; activation explicite,
READ COMMITTED, timeouts bornés, producteurs drainés, locks SHARE ROW EXCLUSIVE sur protocole,
autorités, quatre sources/associations et journal, backfill UUID reproductible, contrôle complet,
flip et journal atomiques. UNKNOWN se réconcilie par operation UUID ; ACTIVE n'est pas renuméroté.
[S10] et [runbook](../operations/event-sequencing-runbook.md).

`pocoma_event_runtime` reçoit les droits limités V30, pas les fonctions administratives V31.
Propriétaire, privilèges hérités et rôles déployés restent réserve R4, schéma non-public réserve R2.
Les scripts de cutover Event→Task [S38] ne créent pas un registre de flux A1.
La politique indique **Trusted baseline: NONE**, malgré sa section historique citant V23 ;
le schéma CURRENT du code est V31. Ne pas inventer une baseline V31 certifiée.

## 3. Architecture TARGET proposée et alternatives

### 3.1 Responsabilités et contrat d'entrée

Conserver A1 intégralement : unité `(family,consumptionId,partitionId)`, flux mono-famille,
génération immuable, un worker logique aligné, tous les Events locaux, parcours E contigu,
CAS checkpoint + slots dans une transaction PRIMARY. Aucun filtre métier, acquisition Claim,
retry métier ou exécution dans cette transaction. Une invocation publique traite **un seul lot borné**.
A2 livre le service et ses ports ; la boucle de reprise A3 et l'orchestration/polling A4 restent futures.

Registre `consumption_streams` distinct des checkpoints : UUID global identifie une seule famille,
une référence **et empreinte** de règles immuables, nombre de partitions, algorithme de partition,
version de codec. `active` est le seul champ mutable. UUID ne dérive ni d'un hostname ni du nombre
d'instances ; ne jamais réutiliser un UUID pour des règles nouvelles. Changement N/algorithme/règles
⇒ nouvelle génération administrative depuis zéro ; l'ancienne peut coexister, puis être suspendue.
Il n'y a pas de déplacement automatique de ses slots ou de reset de checkpoints.

Les règles appartiennent au consumer ; Discovery traite leur référence/digest comme opaques.
Avant activation, la composition valide que le manifeste local correspond exactement au registre
(famille, UUID, N, codec, digest). Référence mutable vers « latest » interdite. Les partitions sont
`0..N-1`, chaque checkpoint initialisé à zéro. `active=false` suspend **l'enrôlement seulement** ;
les slots déjà durables restent exécutables par Consumption. Un arrêt d'exécution nécessite sa
propre commande d'exploitation, sans révoquer artificiellement les claims.

### 3.2 Deux solutions de slots, comparaison complète

| Dimension | R — extension relationnelle additive | I — identités existantes, codec E versionné (préférée) |
|---|---|---|
| Schéma | Ajouter aux slots `consumption_id UUID NULL`, `event_family TEXT NULL`, `event_id UUID NULL`, `partition_id INT NULL`, FK registre, CHECK tout-null non-E / tout-présent E ; unique partiel `(consumption_id,event_id)` ; index flux/partition/due. Garder les quatre colonnes actuelles. | Garder colonnes et lifecycle ; `consumable_type=family`, `consumable_components=[eventId]`, nouveau `consumer_type='E_CONSUMPTION_V1'`, `consumer_components=[consumptionId,partitionId]` (deux **strings**). Ajouter deux index E et CHECK de forme, §4.2. |
| Unicité / intégrité | FK et types natifs ; MAIS l'unique actuel bloque les générations si les anciennes clés sont conservées identiques. Il faut aussi les encoder/versionner, ou changer l'ancien unique et toutes ses inférences ON CONFLICT. | Unique actuel réutilisé pour acquisition ; unique E supplémentaire ignorant partition empêche un second slot `(family,eventId,cid)` affecté par erreur ailleurs. Validation registre et hash dans l'adapter/codec ; pas de FK JSONB automatique. |
| Coût SQL | Colonnes/FK supplémentaires pour chaque slot E, index naturels compacts ; changer insert, recherches et mapping des nouveaux slots sans diverger des anciennes API. | Même INSERT que le lifecycle ; deux arrays courts, coût B-tree JSONB conservé. Recherche par égalité d'identité consumer indexée, aucune anti-jointure sur l'histoire. Deux index supplémentaires pour E seulement. |
| Java / compatibilité | Modifier le mapping JPA et les paramètres de création/recherche ou créer une voie spécialisée garantissant les colonnes ; acquisition générique ne doit pas créer un E incomplet. Risque d'identités doubles et plus de branches dans le persistence layer commun. | Aucun changement requis aux ports lifecycle, entités existantes ou CAS S. Un codec partagé et un reader spécialisé borné suffisent ; `List<String>` et Jackson sont déjà supportés. Anciennes clés disjointes par namespace. |
| Migration / retour | Colonnes nullables préservent non-E ; backfill exige mapping ancien consumer→UUID figé et partition ; DDL et compatibilité ancien binaire à tester. | Pas de réétiquetage historique ; nouvelles générations depuis C=0, anciens slots intacts. Retour binaire garde les nouveaux slots dormants ; ne pas remettre les anciens workers sur de nouveaux effets sans procédure de bascule. |
| Limite | Plus de changements génériques que nécessaire à A2, double représentation tant que cleanup différé. | Contrat de codec durable à conserver/migrer explicitement plus tard ; taille/coût JSONB à mesurer en A5. Dépend de writers de confiance pour cohérence registre/partition. |

**Choix proposé : I.** Il respecte A1 sans refondre Consumption : le même chemin d'acquisition trouve
le slot enrôlé, ses quatre paramètres sont déjà acceptés, fencing/provenance/outcomes/non-E restent
inchangés. L'index d'unicité E n'est pas optionnel : inclure partition dans la seule clé actuelle
permettrait deux slots du même Event/flux si le routage était incohérent. L'index de sélection est
le minimum pour remettre à A3 une partition indexable. R reste viable si la revue exige une FK native
consumption→registre dans chaque slot ; ce besoin justifierait alors son coût et une nouvelle déclaration
d'impact. Une deuxième table `consumption_slots_e` dupliquant claims/finalisation est écartée pour A2.

Bijection canonique sur les données valides :
`(family,eventId,cid) ↔ (family,[uuidLower(eventId)],'E_CONSUMPTION_V1',[uuidLower(cid),decimal(pid)])`,
avec `pid = floorMod(hashPersisté(eventId),N(cid))` unique. Pas de zéros en tête ni de nombres JSON
pour pid. Family utilise l'enum A1 : BUSINESS_EVENT et BINDING_FACT diffèrent volontairement des
anciens types `EVENT` et `IDENTITY_BINDING_FACT`. Le namespace consumer assure la séparation,
même pour COMMAND_TERMINAL_EVENT qui garde le même nom. Test codec Java ↔ JSONB ↔ codec exigé.

### 3.3 Coexistence et migration des anciens slots

A2 n'attribue **pas** rétrospectivement un UUID arbitraire aux slots anciens. Le registre ne connaît
pas automatiquement les versions de leurs règles. Créer des UUID administratifs neufs et commencer
à zéro garantit une couverture indépendante ; les anciens DONE ne dispensent pas d'enrôler le nouveau
flux. Claims, inputs/results et effets historiques restent liés à leurs slot_id d'origine.

La coexistence est sûre pour les identités et l'enrôlement ; elle ne garantit pas automatiquement
la sûreté des **effets métier** entre générations. Idempotence/monotonie/commutativité des consumers
et éventuel store de reconstruction sont requis avant leur activation d'exécution. Pour l'actuel
Event→Task, plusieurs anciens slots par projection ne se fusionnent pas en un nouveau slot sans
preuve du fan-out complet. Les services CURRENT savent traiter des conséquences, pas encore une
génération complète opaque. Ne pas les brancher silencieusement dans A2.

A2 peut être exercé avec flux inactifs par défaut, activation administrative en base jetable et
invocation explicite du service ; les anciens workers continuent à utiliser leurs clés. Le pont
Consumption devra être prouvé en tests par codec + acquisition/finalisation réels. La recherche pending,
le dispatch réel des règles et la bascule des workers seront cadrés A3/A4 ; pas de scheduler ou de
nouveau consumer métier UserCreated inventé pour clôturer A2. Aucun flux E d'exécution production
n'est activé par cette livraison.

## 4. DDL et SQL envisagés

### 4.1 Registre immuable et checkpoints

Migrations **nouvelles après V31**, numéros à réserver au HEAD d'implémentation ; V32/V33 ci-dessous
sont des noms proposés, pas des fichiers créés par A2-P. Schéma de déploiement qualifié explicitement.

```sql
CREATE TABLE consumption_streams (
  consumption_id uuid PRIMARY KEY,
  event_family text NOT NULL CHECK (event_family IN
    ('BUSINESS_EVENT','COMMAND_TERMINAL_EVENT','BINDING_FACT','USER_CREATED_FACT')),
  partition_count integer NOT NULL CHECK (partition_count > 0),
  partition_algorithm text NOT NULL CHECK (partition_algorithm = 'PERSISTED_HASH_FLOORMOD_V1'),
  key_codec_version integer NOT NULL CHECK (key_codec_version = 1),
  rules_ref text NOT NULL CHECK (btrim(rules_ref) <> ''),
  rules_sha256 text NOT NULL CHECK (rules_sha256 ~ '^[0-9a-f]{64}$'),
  active boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (event_family,consumption_id,partition_count)
);
CREATE TABLE discovery_checkpoints (
  event_family text NOT NULL,
  consumption_id uuid NOT NULL,
  partition_count integer NOT NULL,
  partition_id integer NOT NULL CHECK (partition_id >= 0 AND partition_id < partition_count),
  last_examined_position bigint NOT NULL DEFAULT 0 CHECK (last_examined_position >= 0),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (event_family,consumption_id,partition_id),
  FOREIGN KEY (event_family,consumption_id,partition_count)
    REFERENCES consumption_streams(event_family,consumption_id,partition_count) ON DELETE RESTRICT
);
```

`partition_count` du checkpoint est une copie contrainte par FK permettant `pid<N` sans lookup
non déterministe dans un CHECK. Une fonction trigger BEFORE UPDATE/DELETE du registre refuse DELETE
et tout changement de tuple `(cid,family,N,algorithm,codec,rules_ref,digest,created_at)` ; seule active
peut varier. Checkpoint : identité/N immuables, DELETE interdit et baisse de C interdite ; un reset
nécessite une nouvelle génération. Runtime n'a pas de droit d'écriture sur le registre ni INSERT/DELETE
checkpoint ; UPDATE limité à `(last_examined_position,updated_at)` ; bootstrap administratif seul.
Les handlers de trigger valident ces invariants en SQL et ne lancent ni transaction ni réparation ;
les comparer à OLD/NEW par `IS DISTINCT FROM` pour couvrir NULL correctement.
Le service utilise le CAS exact, pas un UPDATE libre ; ne pas prétendre qu'un grant de colonnes
prouve à lui seul la bonne couverture d'une plage.

### 4.2 Conservation des slots, deux index E

```sql
-- Les quatre colonnes historiques et uk_consumption_slots_key sont conservés.
ALTER TABLE consumption_slots ADD CONSTRAINT ck_consumption_slots_e_v1_shape CHECK (
  consumer_type <> 'E_CONSUMPTION_V1' OR (
    consumable_type IN ('BUSINESS_EVENT','COMMAND_TERMINAL_EVENT','BINDING_FACT','USER_CREATED_FACT')
    AND jsonb_array_length(consumable_components) = 1
    AND jsonb_typeof(consumable_components->0) = 'string'
    AND jsonb_array_length(consumer_components) = 2
    AND jsonb_typeof(consumer_components->0) = 'string'
    AND jsonb_typeof(consumer_components->1) = 'string'
    AND (consumable_components->>0) ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    AND (consumer_components->>0) ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    AND (consumer_components->>1) ~ '^(0|[1-9][0-9]*)$'
  )
);
CREATE UNIQUE INDEX uk_consumption_slots_e_generation_event
 ON consumption_slots(consumable_type,consumable_components,(consumer_components->>0))
 WHERE consumer_type = 'E_CONSUMPTION_V1';
CREATE INDEX idx_consumption_slots_e_partition_due
 ON consumption_slots(consumer_components,status,next_claim_at,slot_id)
 WHERE consumer_type = 'E_CONSUMPTION_V1';
```

Les CHECK préexistants imposent déjà les arrays. Valider que les expressions manquantes ne puissent
pas faire accepter une forme invalide par NULL SQL ; tests explicites null/nombre/array vide requis.
Le codec/registre contrôlent UUID, pid int et borne N, famille et hash de chaque slot. Pas de FK
relationnelle cachée via JSON : writers applicatifs autorisés constituent la frontière de confiance.
Une incohérence de partition trouvée après un conflit est une erreur d'intégrité, jamais DO UPDATE.

La sélection à remettre à A3 est indexable sans source historique (requête **pas encore un worker A3**) :

```sql
SELECT slot_id,consumable_type,consumable_components,current_claim_id,next_claim_at
FROM consumption_slots
WHERE consumer_type='E_CONSUMPTION_V1'
  AND consumer_components=jsonb_build_array(:cid::uuid::text,:pid::integer::text)
  AND status='PENDING' AND next_claim_at<=:now
ORDER BY next_claim_at,slot_id LIMIT :k;
-- A3 joindra les claims par PK pour due/Busy/lease et relira la source par UUID.
```

À l'implémentation, vérifier l'index partiel dans EXPLAIN ; le prédicat consumer_type est littéral
pour ne pas dépendre d'une inférence hasardeuse de plan préparé générique. Aucun index modulo
source supplémentaire dans A2. CREATE INDEX ordinaire prend des verrous bloquant les writers ;
prévoir fenêtre/drainage avec timeouts. Si zéro interruption est exigé, revoir explicitement la
migration (CREATE INDEX CONCURRENTLY hors transaction + validation du CHECK), sans le glisser dans
la migration Flyway transactionnelle. Aucun SLO de déploiement n'est présumé.

### 4.3 Hash UserCreated : proposition sans modifier l'append Java

Ajouter `partition_hash` **GENERATED ALWAYS STORED**, dérivé de `user_id`, pour le calculer à chaque
INSERT et matérialiser l'historique par ALTER TABLE. Cela évite UPDATE du fact interdit par son
trigger d'immutabilité et préserve les anciens INSERT à colonnes explicites. Ne pas désactiver
les triggers d'immutabilité pour faire un backfill ad hoc.

Calcul proposé : XOR des quatre mots de 32 bits du UUID, équivalent signé Java UUID.hashCode,
sans dépendance à `hashtext` ni au seed serveur :

```sql
CREATE FUNCTION uuid_partition_hash_v1(u uuid) RETURNS integer
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
 SELECT (('x'||substr(replace(u::text,'-',''), 1,8))::bit(32)::integer)
      # (('x'||substr(replace(u::text,'-',''), 9,8))::bit(32)::integer)
      # (('x'||substr(replace(u::text,'-',''),17,8))::bit(32)::integer)
      # (('x'||substr(replace(u::text,'-',''),25,8))::bit(32)::integer)
$$;
ALTER TABLE user_created_facts ADD COLUMN partition_hash integer
 GENERATED ALWAYS AS (uuid_partition_hash_v1(user_id)) STORED NOT NULL;
```

Qualifier fonction/schéma dans le vrai DDL ; fonction versionnée, pas de CREATE OR REPLACE pour
changer le calcul sous un même nom. Droits d'exécution nécessaires aux writers à vérifier, PUBLIC
révoqué selon le modèle de rôle retenu. La colonne existante user_id est NOT NULL et les facts
restent immuables. Cette opération prend ACCESS EXCLUSIVE et peut réécrire la table : mesurer
volume, durée, WAL, fenêtre et restauration avant déploiement ; ce n'est pas un backfill sans coût.
Vérifier PostgreSQL 17 réel, restore, `INSERT ... RETURNING`, writer Registration/ancien binaire,
FK et triggers E après ajout. Pas d'index hash seul nécessaire : lookup source par PK après seek E.

### 4.4 Droits et fonctions de lock étroites

Séparer rôle runtime Discovery et maintenance, propriétaires de confiance, schéma qualifié,
PUBLIC révoqué. Discovery lit sources/associations/protocole, insère slots et avance checkpoint ;
ne réserve pas E et ne met à jour aucun fact/claim/outcome. Pas de nouvelle activation au démarrage.

Attention : PostgreSQL exige aussi le privilège UPDATE sur au moins une colonne pour les SELECT
avec clause de verrouillage. Un simple GRANT SELECT au registre ne permet donc pas FOR SHARE.
Éviter de donner UPDATE(active) au runtime. Prévoir deux fonctions étroites SECURITY DEFINER :

```text
lock_active_discovery_stream(cid):
  SELECT configuration FROM trusted_schema.consumption_streams
    WHERE consumption_id=cid AND active FOR SHARE;
  -- 0 ligne => désactivé/inconnu, aucune écriture autorisée par l'appel
lock_discovery_stream_for_reconciliation(cid):
  SELECT configuration FROM trusted_schema.consumption_streams
    WHERE consumption_id=cid FOR UPDATE;
  -- autorise seulement l'observation et le verrou, y compris inactive ; aucun UPDATE
```

Leurs corps SQL exacts/RETURNS seront livrés avec la migration, search_path épinglé
`pg_catalog,<schéma de confiance>,pg_temp`, paramètres bindés, owner non runtime, aucun nom de table
fourni par l'appelant. Les row locks restent détenus dans la transaction de l'appelant. Vérifier les
EXECUTE grants et l'absence d'échappement de schéma comme en V30, sans élargir V31 ni fermer R2/R4
sans preuve. Ces fonctions ne possèdent et ne committent aucune transaction.

## 5. Protocole transactionnel complet proposé

### 5.1 Initialisation idempotente, puis activation explicite

```text
ADMIN BEGIN READ COMMITTED
  INSERT consumption_streams(cid,family,N,algorithm,codec,rules_ref,digest,active=false)
    ON CONFLICT(consumption_id) DO NOTHING;
  SELECT ... WHERE consumption_id=:cid FOR UPDATE;
  comparer strictement tous les champs immuables à la demande ; divergence => ROLLBACK/erreur
  INSERT discovery_checkpoints(family,cid,N,partition_id,last_examined_position=0)
    SELECT family,cid,N,i,0 FROM generate_series(0,N-1) i
    ON CONFLICT(event_family,consumption_id,partition_id) DO NOTHING;
  vérifier exactement N checkpoints, mêmes bornes/identités ; conserver les C existants
ADMIN COMMIT

ADMIN BEGIN READ COMMITTED
  SELECT stream ... FOR UPDATE;
  vérifier configuration/manifeste/capacité N, N checkpoints et protocole E v1 ACTIVE
  vérifier activation E autorisée dans cet environnement et historique couvert (V31)
  UPDATE consumption_streams SET active=true WHERE consumption_id=:cid;
ADMIN COMMIT
```

Pas de `ON CONFLICT DO UPDATE` effaçant C ou changeant les règles. Bootstrap concurrent sérialisé
par l'unique UUID puis lock du registre ; rollback atomique sans stream à moitié initialisé.
UUID de demande de création stable : après UNKNOWN du bootstrap, même cid, relire et comparer puis
compléter idempotemment ; ne pas créer une génération supplémentaire. La future composition peut
rester passive même avec E PREPARED ; toute tentative d'enrôlement actif y est refusée.
Activation de flux ≠ activation E ≠ permission d'exécuter un nouveau consumer.

### 5.2 Owner et lecture du checkpoint

Owner A2 distinct des owners de consommation, transaction physique PRIMARY JPA standard,
READ COMMITTED explicitement fixé et vérifié, entrée publique sans transaction/synchronisation
ambiante. `JdbcTemplate` rejoint la même datasource/connexion ; participants MANDATORY ;
contrôle de participation physique dans le corps de l'adapter (comme
[PrimaryWriteParticipation.require](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/transaction/PrimaryWriteParticipation.java#L20)),
y compris appel construit par `new`, pour refuser une écriture en autocommit ou sur mauvaise datasource.
Pas d'autocommit intermédiaire, REQUIRES_NEW, savepoint local ou callback métier.
Le service d'enrôlement n'a pas besoin de collecter des publications ni de déclencher un suffixe E.

```text
BEGIN READ COMMITTED
  SET LOCAL lock_timeout=<budget technique validé>;
  SET LOCAL statement_timeout=<budget technique validé>;
  stream = lock_active_discovery_stream(:cid);           -- row SHARE sur configuration
  vérifier family, pid, N, codec, algorithme, empreinte attendus par l'appelant
  vérifier event_sequencing_protocol singleton/v1/ACTIVE -- lecture seule, mode administré offline
  C = SELECT last_examined_position FROM discovery_checkpoints
      WHERE event_family=:family AND consumption_id=:cid AND partition_id=:pid;
  exiger une ligne et N cohérent ; pas de création lazy dans cette transaction
```

**Aucun FOR UPDATE/FOR SHARE du checkpoint avant le CAS.** Le FOR SHARE du **registre** est une
barrière d'activation, pas une élection des discoverers : deux batches du même triplet peuvent
le tenir simultanément et doivent encore résoudre leur CAS. Ce lock explicite est à valider en
revue comme mécanisme d'arrêt A1 ; prétendre qu'un simple test `active=true` protège le commit
contre une désactivation serait incorrect.

### 5.3 Lecture de positions puis jointure de métadonnées

Une requête par famille issue d'un switch fermé de l'enum, noms de tables constants, jamais de
concaténation d'un nom SQL fourni par la configuration. Exemple BUSINESS_EVENT :

```sql
WITH page AS MATERIALIZED (
 SELECT position,event_id FROM business_event_positions
 WHERE position>:C ORDER BY position LIMIT :k
)
SELECT page.position,page.event_id,source.id AS source_id,source.pot_partition_hash AS hash
FROM page LEFT JOIN business_event_outbox source ON source.id=page.event_id
ORDER BY page.position;
```

Même statement = même snapshot READ COMMITTED pour positions et sources. Trois substitutions :

| Famille | Association | Source / clé / hash |
|---|---|---|
| COMMAND_TERMINAL_EVENT | `command_terminal_event_positions` | `command_terminal_events / event_id / command_partition_hash` |
| BINDING_FACT | `binding_fact_positions` | `external_identity_binding_facts / event_id / partition_hash` |
| USER_CREATED_FACT | `user_created_fact_positions` | `user_created_facts / event_id / partition_hash` nouveau |

En mémoire sur au plus k métadonnées, numéroter les lignes `i=1..page.size` : vérifier
`position-C=i` avec arithmétique contrôlée, source_id/hash non null. Ne pas incrémenter un expected
après une dernière position Long.MAX_VALUE. Toute anomalie annule **le lot entier**
et déclenche un diagnostic, sans franchir la position manquante ni tenter de la réparer. Le LEFT JOIN
conserve l'anomalie ; un INNER JOIN suivi d'un max pourrait la masquer. Pour C=Long.MAX_VALUE,
retour épuisé sans addition débordante. `k>0`, borne maximale de lot à fixer avant implémentation.

Si page vide, conserver C et retourner EMPTY après résolution de la transaction ; pas de CAS C→C
inutile. Une publication producteur en cours est absente du snapshot : sous 4E ACTIVE conforme,
un futur commit ne peut apparaître sous un préfixe déjà visible ; le prochain lot la voit. Un trou
visible au milieu d'une page n'est **pas** interprété comme un commit tardif normal.

Si page non vide, N = **dernière position de la page entière**, avant tout filtre. Aucun `max`
d'une sélection filtrée, aucun saut au compteur E. A2 ne verrouille et ne met à jour aucune autorité E.
L'inspection globale de densité reste administrative ; le seek hot path n'exécute pas de COUNT historique.
Une queue manquante sans position supérieure visible n'est pas détectable par ce seul seek :
complétude V31/4E et frontière de confiance restent des préconditions explicites.

### 5.4 Partition, insertion et contrôle des doublons

Pour chaque métadonnée, calculer pid depuis **hash persisté** et N immuable avec bigint modulo.
Insérer un slot uniquement si pid demandé. Aucun EventType, catalogue projection, état métier,
DONE ou délai temporaire n'intervient dans ce filtre. Ne pas charger le payload.

Dans l'ordre croissant de position de la page, pour chaque Event local :

```sql
INSERT INTO consumption_slots
 (slot_id,consumable_type,consumable_components,consumer_type,consumer_components,
  revision,last_attempt_number,status,next_claim_at,created_at)
VALUES
 (:slot_uuid,:family,jsonb_build_array(:event_id::uuid::text),'E_CONSUMPTION_V1',
  jsonb_build_array(:cid::uuid::text,:pid::integer::text),0,0,'PENDING',:batch_now,:batch_now)
ON CONFLICT (consumable_type,consumable_components,consumer_type,consumer_components)
DO NOTHING;
```

Capturer un batch_now commun de la DB après BEGIN ; création et échéance égales respectent V4.
Après chaque insertion ignorée, relire dans un **statement distinct** la clé exacte (sans lock)
et contrôler codec/famille/cid/pid attendus ; READ COMMITTED peut alors voir le gagnant qui n'était
pas visible dans le snapshot du INSERT. Exiger un slot présent, tout état autorisé, ne rien réécrire.
L'unique E détecte un doublon dans une autre partition : 23505 hors cible ON CONFLICT ⇒ rollback,
puis diagnostic de configuration, pas de retry aveugle ni réaffectation automatique.
Collision aléatoire de slot_id ⇒ erreur et rollback, jamais assimilation à un doublon logique.

Une optimisation multi-row INSERT est possible seulement si son ordre effectif et les doublons sont
prouvés ; commencer par JDBC batch ordonné ou insertions séquentielles bornées. Aucun DO UPDATE
sur les colonnes de lifecycle, aucun nouveau Claim, aucune écriture de provenance par Discovery.

### 5.5 CAS et résolution

```sql
UPDATE discovery_checkpoints
SET last_examined_position=:N,updated_at=clock_timestamp()
WHERE event_family=:family AND consumption_id=:cid AND partition_id=:pid
  AND last_examined_position=:C;
```

Exiger **exactement 1** ligne ; 0 ⇒ exception de conflit qui sort du callback owner et rollback
**tous** les slots insérés par ce perdant, y compris ceux au-delà du N du gagnant. >1 impossible
sous PK, traité comme invariant cassé. N>C pour un lot non vide. COMMIT par l'owner uniquement
après succès du CAS ; le lock registre est gardé jusqu'à la résolution physique.

Un lot entièrement non local avance quand même C→N, même avec zéro INSERT.
Ni DONE, ni NotReady, ni Busy des slots existants ne retardent Discovery.
Crash avant commit ⇒ slots et C annulés ; crash après commit ⇒ C et slots présents, Consumer
peut continuer sous le checkpoint. Le curseur n'atteste jamais leur terminaison.

### 5.6 Concurrence et verrous PostgreSQL, y compris avant CAS

| Opération | Locks/effets attendus, à observer en PostgreSQL |
|---|---|
| SELECT page/source/checkpoint | ACCESS SHARE de tables ; visibilité MVCC, pas de row lock de source/checkpoint, pas de predicate/gap lock à READ COMMITTED. Les tables immuables/complétude 4E fournissent la stabilité utile. |
| Config FOR SHARE | ROW SHARE de table et SHARE de ligne registre ; compatibles entre discoverers, incompatibles avec UPDATE(active), FOR UPDATE et FOR NO KEY UPDATE sur la même ligne. FOR KEY SHARE **ne suffit pas** pour bloquer un UPDATE non-clé active. |
| INSERT slot / uniques | ROW EXCLUSIVE table, vérifications B-tree et insertion spéculative ON CONFLICT ; attente possible sur transaction/insertion non committée concurrente avant d'atteindre le CAS. DO NOTHING n'est pas « sans attente ». Collision non couverte par arbitre ⇒ erreur SQL. |
| Slot déjà existant | DO NOTHING n'élit pas l'exécuteur, ne remplace pas le claim et ne prend pas le FOR UPDATE explicite d'Acquire. Les attentes d'unicité, DDL ou chemins concurrents restent possibles. |
| CAS checkpoint | ROW EXCLUSIVE table, verrou de ligne généralement FOR NO KEY UPDATE (champs non-clés) ; peut attendre une transaction gagnante puis PostgreSQL réévalue C après son commit en READ COMMITTED ⇒ 0 pour le perdant. Aucun compteur E touché. |
| Bootstrap FK checkpoint→registre | Probe FK et KEY SHARE sur clé référencée, en plus du lock administratif du registre ; pas sur le chemin normal d'insertion des slots I. |
| Consumption concurrente | Acquire : INSERT conflict puis S FOR UPDATE, claims/FK ; exécution : effets/provenance/flush/CAS S/clôture C. Aucun accès checkpoint ou lock registre pendant qu'elle détient S dans la proposition. Les nouvelles clés doivent être identiques. |
| Désactivation | UPDATE active, lock non-clé sur registre, attend les SHARE des batches ; aucune écriture slots/checkpoints dans cette transaction administrative. |

Deux contenders lisent C, insèrent en même ordre ; l'un peut attendre l'autre **sur le premier slot**,
puis perdre le CAS après son commit. Avec zéro Event local, ils se concurrencent directement au CAS.
Avec k différents, le perdant peut avoir inséré un suffixe supplémentaire : son rollback doit le
retirer. Un ordre stable réduit les cycles ; il ne démontre pas l'absence générale de deadlock,
notamment avec acquisition concurrente, opérations administratives ou écrivains non conformes.

40P01 (deadlock), 40001 si émis par PostgreSQL, lock_timeout/statement_timeout,
erreur SQL et CAS perdu ⇒ rollback confirmé, puis **nouvelle** transaction avec relecture config/C,
backoff technique borné au caller ; pas de sous-retry SQL dans une transaction avortée. Les erreurs
permanentes (source absente, codec/unique incohérent) exigent diagnostic, pas une boucle infinie.
Ne pas reprendre la policy failure Consumption pour ces conflits de Discovery.
REPEATABLE READ/SERIALIZABLE ne sont pas requis ; l'owner refuse une isolation différente du contrat.
Les fonctions de lock sont déclarées VOLATILE : STABLE/IMMUTABLE est incompatible avec leur SELECT verrouillant.

### 5.7 COMMIT UNKNOWN et reprise auditable

Le résultat de l'API distingue au minimum COMMITTED(N), EMPTY(C), INACTIVE, CONFLICT_ROLLED_BACK,
FAILED_ROLLED_BACK, UNKNOWN(cid,pid,C,N). Une exception réseau de résolution après retour complet
du callback n'est pas convertie en rollback certain sur la seule foi de Hibernate. Ne pas annoncer
l'enrôlement réussi avant commit confirmé ; aucune failure métier ou clôture de slot sur UNKNOWN.

Une simple nouvelle lecture C'=C **ne prouve pas** le rollback : le backend initial peut encore
committer. Proposition de barrière exceptionnelle, sur nouvelle connexion PRIMARY :

```text
BEGIN READ COMMITTED
  lock_discovery_stream_for_reconciliation(cid); -- FOR UPDATE registre, même inactive
  -- attend TOUS les batches antérieurs tenant FOR SHARE, même s'ils n'ont pas atteint le CAS
  -- bloque les nouveaux batches le temps de cette inspection
  SELECT checkpoint et configuration dans un statement suivant ; vérifier invariants
  si C' >= N : couverture du lot durable (par tentative initiale ou contender équivalent)
  si C' = C : aucun avancement du lot n'a persisté après résolution des détenteurs antérieurs
  si C < C' < N : un autre lot plus petit a gagné ; reprendre depuis C'
  si C' < C : corruption/reset interdit => STOP
COMMIT
```

Au besoin vérifier les slots locaux de la plage reconnue par lookup sur leurs clés/index, tout état
admis ; aucun COUNT historique massif. La preuve porte sur la **couverture durable**, pas sur
l'identification exacte du backend gagnant ; pas de journal de lots supplémentaire nécessaire.
Une fois la barrière obtenue, aucune ancienne transaction d'enrôlement conforme ne peut encore
surgir sous la lecture : elles avaient toutes acquis SHARE avant C. La barrière est par flux,
rare et bornée ; coût acceptable à qualifier. Si timeout, backend non résolu ou impossibilité
de lire : **rester UNKNOWN**, renouveler la réconciliation, pas d'inférence depuis le temps écoulé.
Après confirmation, le prochain appel relit C'/active et repart normalement, sans réutiliser une
liste de slots calculée sous l'ancienne transaction. Même règle après crash du processus ; un
redémarrage ordinaire peut lancer une réconciliation avant de reprendre un triplet incertain.

### 5.8 Désactivation pendant l'enrôlement

```sql
-- admin, transaction autonome, sans lock S/checkpoint avant celui-ci
BEGIN;
UPDATE consumption_streams SET active=false WHERE consumption_id=:cid;
COMMIT;
```

Si un batch détient SHARE, l'UPDATE attend son COMMIT/ROLLBACK ; ce batch est autorisé à finir.
Si l'UPDATE est passé d'abord, le SELECT FOR SHARE concurrent attend puis réévalue `active=true`
et ne retourne aucune ligne : batch INACTIVE sans slots/CAS. Après **commit confirmé** de la
désactivation et tant que le flux reste inactif, aucun nouvel enrôlement autorisé ne peut committer ; les précédents
sont déjà résolus. Timeout administratif ⇒ arrêt non confirmé. Réactivation du même registre
reprend les checkpoints, sans reset. Une coupure d'exécution des claims relève d'une autre politique.

## 6. Inventaire des fichiers à créer/modifier lors d'A2

Les chemins proposés ci-dessous ne sont **pas créés par A2-P**. Packages sous
`src/main/java/com/kartaguez/pocoma/` ; suffixes de migrations à réserver. Éviter un nouveau module
Maven : `engine-consumption` dépend déjà de `port-transaction` (EventFamily) et `domain-consumption`.
Aucune dépendance vers un métier concret n'est ajoutée. La revue doit confirmer ce placement du
contrat Discovery dans la capacité existante avant implémentation ; un module séparé serait un
changement de plan et de gates, pas une initiative cachée.

| Fichier / zone cible | Nature et contenu strictement nécessaire |
|---|---|
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/port/in/discovery/EnrollDiscoveryBatchUseCase.java`, `DiscoveryStreamPartition.java`, `DiscoveryBatchResult.java` | Nouveaux contrats mono-famille/triplet/config attendue, un lot, résultats de résolution ; noms à stabiliser. |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/port/out/discovery/DiscoveryEnrollmentPersistencePort.java` | Participant de persistance transactionnelle : config gardée, C, page typée, insertion, CAS ; aucune méthode acquire/execute. |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/discovery/EnrollDiscoveryBatchService.java`, `EventStreamConsumptionKeys.java` | Validation contiguïté/partition, codec partagé, ordre, erreur CAS ; pas de règle métier. |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/transaction/discovery/TransactionalEnrollDiscoveryBatchUseCase.java` | Wrapper propriétaire via runner injecté, délègue au participant, ne masque pas rollback/UNKNOWN. |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/discovery/JdbcDiscoveryEnrollmentAdapter.java` | Switch des quatre SQL, même JdbcTemplate PRIMARY, MANDATORY, contrôles rows affected et conflit. |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/transaction/discovery/PrimaryDiscoveryTransactionRunner.java` | Owner JPA standard, garde ambient/isolation, résolution UNKNOWN après transport et cleanup ; pas de registre PublishedEvents ni E. |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V32__discovery_streams_and_checkpoints.sql` proposé | Registre/checkpoints, immutabilité, lock functions/droits, codec CHECK et deux index E. Aucun changement V1–V31. |
| `app/infra-persistence-primary-jpa/src/main/resources/db/migration/V33__user_created_fact_partition_hash.sql` proposé | Fonction UUID versionnée et colonne générée STORED, contrôle historique/droits. Pas d'UPDATE/disable trigger. |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/discovery/DiscoveryEnrollmentConfiguration.java` | Factory/composition explicite passive, validation manager/EMF/datasource ; import ciblé si nécessaire, aucun nouveau scheduled worker ni auto-activation. Pas de scan global implicite. |
| `app/engine-consumption/src/test/.../discovery/*Test.java` | Codec, bornes de page/continuité et orchestration sans DML hors protocole ; tests PostgreSQL demeurent la preuve de concurrence. |
| `app/infra-persistence-primary-jpa/src/test/.../adapter/discovery/DiscoveryEnrollmentPostgresTest.java`, `DiscoveryEnrollmentConcurrencyPostgresTest.java`, `DiscoveryConsumptionCompatibilityPostgresTest.java` | Matrice §7 avec vrais adapters lifecycle et manager PRIMARY ; quatre sources réelles. |
| `app/infra-persistence-primary-jpa/src/test/.../migration/DiscoverySchemaUpgradePostgresTest.java` | V31→nouveau CURRENT, hash ancien/nouveau writer, contraintes/droits et retour binaire dormant. |
| `app/runtime-event-consumption-worker/src/test/.../discovery/DiscoveryEnrollmentRuntimePostgresTest.java`, fixture/proxy/process de test si requis | Garde owner, identité physique JPA/JDBC, transport COMMIT, kill backend/process ; pas de lancement du polling futur. Réutiliser la méthode 4E, sans coupler les POM de runtimes entre eux. |
| `docs/operations/discovery-enrollment-runbook.md`, futur rapport et `docs/testing/evidence/Discovery_A2_*.json` | Bootstrap/activation/arrêt/reconcile/migration, SHA, commandes, résultats non exécutés explicités ; fournir après implémentation. |

**Fichiers CURRENT à préserver** : `JpaConsumptionSlotEntity`, `JpaConsumptionSlotRepository`,
`JpaConsumptionLifecycleAdapter`, `JpaConsumptionProvenanceAdapter`, ports et services Acquire/
Execute/Finalize/Failure, supras et anciens discoverers S20–S29, migrations historiques, stores READ,
configurations d'exécution et POM. Ajouter la spécialisation/codec n'autorise pas leur nettoyage.
`JdbcUserCreatedFactAdapter` ne nécessite pas de changement avec colonne générée ; tester son vrai
INSERT existant. Tout besoin de modification de ces surfaces doit être justifié/re-déclaré avant
édition ; les ports métiers ne reçoivent pas consumptionId par propagation générale.

## 7. Matrice des preuves PostgreSQL attendues

**Statut commun : À ÉCRIRE / NON EXÉCUTÉ pour A2.** Aucun PASS présumé depuis 4E.
PostgreSQL 17 (images actuellement utilisées dans les tests du dépôt), vraies migrations et sources,
manager JPA PRIMARY et JdbcTemplate communs ; pas H2 pour contiguïté/verrous/JSONB/transport.
Fixtures ACTIVE seulement sur bases jetables explicitement activées par administration V31.
Chaque ligne précise la propriété observable ; pas de sleep comme seule preuve d'entrelacement.

| ID | Cas / exécution minimale | Assertions et trace attendues |
|---|---|---|
| P01 | Paramétré sur les **4 familles** : préfixe 1..m, k<m, générations créées depuis 0 | C=N dernière position examinée ; ensemble exact des slots locaux, zéro claim/input/result créé par Discovery ; UUID source et hashes réels, payload non décodé. |
| P02 | Famille avec types métier variés : APPLIED/REJECTED/FAILED ; attach/detach/baseline ; Pot/Expense ; User créé | Tous les Events locaux enrôlés, même un Event sans règle utile ; aucun filtre EventType. No-op réussi prouvé via vraie finalisation Consumption, sans nouveau type d'outcome. |
| P03 | Hash : 0, négatif, INT_MIN/MAX, UUID bit de signe, identités externes distinctes ; N=1,3,7 et borne int | Double modulo bigint = floorMod ; exactement une partition par Event ; union des N partitions = famille, intersection vide ; évidence de l'écart LKV historique sans mutation de celui-ci. |
| P04 | Hash UserCreated, table historique et nouveaux INSERT de Registration | Colonne générée non nulle, SQL XOR = Java UUID.hashCode sur vecteurs fixes + échantillon ; immutable trigger/FK conservés, même event_id/positions/données ; ancien writer compatible et rollback Registration atomique. |
| P05 | Positions locales/non locales intercalées ; lot totalement non local puis lot local | C avance sur toute la page, même zéro slot ; pas max des locaux ; page bornée k ; checkpoint n'attend pas DONE. |
| P06 | Page vide, C=0, limite 1, k refusé, saturation bigint | C stable si vide, aucun UPDATE inutile ni overflow ; aucune recherche non bornée. |
| P07 | Trou intérieur E, source absente/hash NULL sous fixture de corruption contrôlée | Échec fermé, aucun slot ou C durable du lot, anomalie précise ; fixture isolée ne désactive jamais les contraintes dans le vrai chemin applicatif. Précondition de queue intacte distinguée de la détection locale. |
| P08 | Producteur réel bloque réservation E puis commit/rollback, Discovery en parallèle | Pas de checkpoint au-delà d'un Event ultérieurement visible sous C ; rollback producteur ne crée pas de trou ; PID/xid et attentes E observés, Discovery ne verrouille pas E. |
| P09 | Deux discoverers même C et page locale, barrières avant INSERT/CAS | Attente d'unicité **avant CAS** visible par `pg_stat_activity`/`pg_blocking_pids` ; 1 CAS gagnant, perdant rollback puis relecture ; aucun slot en double. |
| P10 | Même triplet, k différents ; d'abord lot court gagnant puis inverse ; 2 lots sans Event local | Les insertions du suffixe du perdant disparaissent ; perdant CAS=0 après attente/recheck, même sans unique slot commun. Aucun effet partiel. |
| P11 | Deux partitions et deux familles distinctes, UUID Event identique entre familles | Checkpoints indépendants ; isolation des clés ; aucune autorité globale E ou consumed partagé créée. |
| P12 | Slot clé exacte déjà PENDING libre, claimed Busy, retry futur et DONE avec outcome/raison | DO NOTHING laisse **toutes** colonnes, claims et provenance identiques ; C avance ; réexécution de page via transaction annulée ne réinitialise rien. |
| P13 | Codec incohérent, UUID/non-string/pid invalide ; même Event/cid proposé dans deux partitions | CHECK/codec refusent ; unique E refuse second slot même sous autre consumer_components ; erreur non assimilée à doublon. Aller-retour Java/SQL et lookup lifecycle exact. |
| P14 | Échec avant INSERT, après j INSERT, après CAS avant COMMIT, rollback-only | Relecture sur connexion indépendante après résolution : C et ensemble de nouveaux slots inchangés ; contraintes différées de commit refusées ⇒ même atomicité. |
| P15 | Kill backend et kill processus pendant le lot, puis redémarrage depuis C durable | Avant commit : aucun partiel ; après commit : slots présents sous C et acquisition possible ; ressources/lock registre libérés après vraie résolution serveur. |
| P16 | Proxy transport laisse COMMIT au serveur puis perd l'accusé ; variante rollback réel et backend encore en cours | UNKNOWN propagé, aucune conversion framework en rollback certain ; barrière registre attend ; C'≥N couvre sans doublon ; variante C'=C et C<C'<N reprend au bon rang. Capturer xid/PID et états avant/après, pas une exception synthétique seule. |
| P17 | Réconciliation avant que le batch inconnu atteigne CAS ; réconciliation qui timeoute | Une lecture simple voit C ancien sans conclure ; FOR UPDATE registre attend le détenteur SHARE ; timeout laisse UNKNOWN. Vérifier blocage des entrants pendant inspection. |
| P18 | Désactivation avant lock, pendant insertion et avant commit ; réactivation | Admin attend batches autorisés ; après commit arrêt, nouveau batch INACTIVE ; slots existants exécutables, checkpoints conservés. Deux SHARE simultanés restent compatibles. |
| P19 | Bootstrap simultané même UUID/config et même UUID/config différente ; erreur au milieu des N checkpoints | N lignes exactes, aucun reset C ; divergence refusée ; activation incomplète impossible ; UNKNOWN création reprend même UUID. |
| P20 | Deux générations actives même famille/Event avec N/règles différents | Deux ensembles de slots indépendants, chaque tuple canonique unique ; changement in-place règles/N/famille/codec/algorithme et DELETE refusés ; nouveau cid depuis zéro. |
| P21 | Acquisition réelle par Consumption sur slot enrôlé, chaque famille et génération | Même slot_id, pas de création parallèle ; TA commit distinct de Discovery ; Execute/Finalize succès/no-op/provenance existante conformes ; lecture par partition utilise l'index proposé. Consumer UserCreated de fixture uniquement, pas une capacité métier livrée. |
| P22 | Takeover, retry, erreur d'exécution et perte du claim après effet provisoire sur slot enrôlé | CAS S/clôture C actuels, rollback de l'effet/provenance stale, Busy/NotReady et lease inchangés ; Discovery n'acquiert pas de claim et ne touche pas l'échéance. Ce test n'implémente pas la boucle de reprise A3. |
| P23 | Deadlock réel contrôlé et timeouts pré-CAS (unique/lock), rollback observé | SQLSTATE et attente capturés, aucune normalisation en succès ; reprise sur nouvelle transaction/config/C uniquement, pas de retry dans transaction avortée. |
| P24 | Nouveau schéma sur V31 PREPARED et ACTIVE jetable, puis ancien binaire sans Discovery | Boot/dml Registration et autres anciennes clés possibles, aucune activation automatique, hashes/positions/données historiques conservés ; nouveaux slots dormants. Pas de promesse downgrade après E ACTIVE vers un binaire incompatible avec E. |
| P25 | Rôles runtime/maintenance distincts, héritages, schéma de test non-public | Runtime ne change pas registre/identités, ne reset pas C ni facts ; lock fonctions autorisées sans UPDATE direct ; PUBLIC sans EXECUTE ; aucune capture search_path. R2 V31 reste distincte si fixture non-public échoue en amont. |
| P26 | Régression non-E : Command, Task, Registration, RegistrationResult, slots historiques E | Identités/acquisition/retry/finalisation/provenance/outcomes inchangés ; scripts preflight/validate restent interprétables ; pas de migration obligatoire des anciens consumers. |
| P27 | Plans sur histoire croissante, même petit lot neuf ; sélection d'une partition de slots | EXPLAIN (ANALYZE,BUFFERS) : seek association position + join PK source + lookup slots/partition indexé ; aucun payload/anti-join historique ; compter rows/loops/buffers et index size/WAL. Coût N scans globalement explicite ; pas de seuil de débit acquis en A2. |

P09/P10/P16–P18/P23 exigent au moins deux connexions physiques et orchestration déterministe par
barrières/latches ; enregistrer PID/xid/isolation, résultats commit/rollback et séquences observées.
La réutilisation du thread/pool après rollback/UNKNOWN doit aussi prouver l'absence de fuite de contexte.
Fixtures et méthodes existantes à étudier/réutiliser, sans les compter comme preuves A2 :
[Discovery Event PostgreSQL](../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/processing/event/JdbcProjectionMaterializationDiscoveryAdapterPostgresTest.java),
[lifecycle PostgreSQL](../../app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/consumption/JpaConsumptionLifecycleAdapterPostgresTest.java),
[transport 4E](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/eventprotocol/EventSequencingTransportPostgresTest.java),
[proxy d'accusé COMMIT](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/eventprotocol/CommitAcknowledgementProxy.java),
[upgrade 4E](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/eventprotocol/EventSequencingUpgradePostgresTest.java).
Les déplacer dans une bibliothèque de test partagée constituerait un changement de dépendances
à déclarer ; préférer une fixture locale minimale à ce stade.
Les tests ciblés nouveaux ne doivent pas être skippés faute de Docker dans la campagne de clôture :
absence d'environnement = preuve manquante, pas PASS. Ne pas modifier les anciens tests pour masquer
une régression ; toute adaptation de fixture imposée par nouveau schéma est explicitée séparément.

## 8. Verification scope et gates prospectifs d'A2

Déclaration **avant implémentation**, à confirmer au SHA du premier lot :

- Expected production impact : nouvelle API passive d'enrôlement E et owner PRIMARY, migrations
  additives registre/checkpoints/codec/index, hash UserCreated ; compatibilité du lifecycle existant.
- Primary slice : **EVENT**, anchor `runtime-event-consumption-worker`.
- Secondary slices : pas de comportement BINDING/LKV/PROJECTION/COMMAND/WEB changé dans l'option I.
  **Complément explicite Registration** pour le writer UserCreated sur nouveau schéma ; monolithe
  pour composition passive si le fragment est raccordé. Ces anchors existent, mais la politique ne
  nomme pas Registration comme slice canonique : ne pas l'assimiler artificiellement à BINDING.
- Forbidden production impact : protocole Claim/CAS S/provenance/outcomes, non-E, READ, routes métier,
  boucle pending A3, polling/équité A4, optimisations/qualification A5, activation E de production.
- Required local proof : P01–P27, tests utiles de codec/validation et PostgreSQL réel, puis EVENT
  canonique ; complément Registration et composition à clôture selon fichiers effectivement raccordés.
- Global architecture gate : **REQUIRED une fois à clôture A2** pour l'introduction intentionnelle
  de la frontière owner Discovery indépendante de Consumption et son isolation (§17.2 de la politique).
  Pas d'ajout `architecture-tests` à chaque itération. Si la revue décide de livrer le fragment
  entièrement dormant sans changement de frontière/composition, réévaluer ce motif explicitement,
  pas une inclusion systématique « par prudence ».
- Full reactor : **NOT REQUIRED pour les sous-lots ni la clôture bornée A2** sans restructuration
  de dépendances ; prochaine obligation à la clôture majeure du chantier A / intégration main.
- Database verification : **CURRENT V31 → nouvelles migrations** ; baseline trusted NONE au SHA.
  Les tests existants bootstrappent via Flyway historique autoritaire, puisqu'aucune baseline trusted
  n'est disponible. Pas de campagne historique exhaustive distincte par défaut. Si une baseline
  devient TRUSTED avant le lot, l'utiliser + nouvelles migrations ; conserver un test d'upgrade
  ciblé V31, sans recertifier toute l'histoire.
- Escalation conditions : nouveau module/POM/frontière supplémentaire ⇒ re-déclarer global avant
  code ; restructuration large ⇒ full requis ; modification lifecycle commun/identités anciennes ⇒
  déclarer les anchors des consommateurs **réellement** affectés avant édition ; refonte métier/non-E
  ou READ ⇒ sortie A2 et arbitrage, pas élargissement silencieux.

Commandes canoniques futures, **depuis `app/`, non exécutées dans A2-P** :

```bash
# A2 inner loop et clôture de la capacité primaire
./mvnw -pl runtime-event-consumption-worker -am test
# Complément writer UserCreated et composition réellement concernés à la clôture
./mvnw -pl runtime-registration-consumption-worker,runtime-monolith -am test
# Gate de frontière global, à clôture A2 seulement
./mvnw -pl architecture-tests -am test
# Futur gate majeur A / intégration main, pas le gate local A2-P ou A2
./mvnw test
```

Si l'option R est retenue et les contrats lifecycle génériques doivent changer, composer les
anchors explicites COMMAND, EVENT, PROJECTION, BINDING, LKV et les compléments Results/Registration
selon impact, **sans -amd et sans remplacer l'analyse par FULL**. Ce n'est pas le scope choisi I.
Les modules compilés/testés par `-am` ne constituent pas des crossings de production.

## 9. Ordonnancement en petits lots contrôlables

Chaque lot exige sa déclaration préalable de scope, un commit indépendant et la mise à jour du
rapport de preuves ; aucun ne commence avant validation A2-P. L'activation production reste un gate
séparé avec les réserves R1–R6 de 4E.

| Lot proposé | Livrable et ordre | Sortie contrôlable / vérification |
|---|---|---|
| A2-1 | Stabiliser codec, types d'entrée/résultats, emplacement des ports et registre immuable ; nouveau DDL registre/checkpoint/index/fonctions sans flux actif. | Revue DDL/clé/roles et P13/P19/P20/P25 ; tests codec et EVENT ; aucune modification lifecycle. |
| A2-2 | Fonction UUID + colonne UserCreated générée ; tests historiques/nouveaux writers et retour. | P04/P24 + complément Registration ; fenêtre ALTER/INDEX explicitée ; aucune désactivation de trigger. |
| A2-3 | Adapter JDBC des 4 seek/joins + service contiguïté/partition/insertion/CAS dans owner réel ; API un batch, composition passive. | P01–P14/P21, EVENT ; preuve même connexion/PID/xid JPA/JDBC, pas d'E/Claim acquis. |
| A2-4 | Concurrence, rollback, crash, résolution UNKNOWN/barrière registre, arrêt/reprise et erreurs pré-CAS. | P09/P10/P14–P23 complets PostgreSQL ; ne pas avancer avec une course non expliquée. |
| A2-5 | Upgrade V31, vieux binaire dormant, non-E, droits/EXPLAIN de base, runbook et rapport de clôture. | P24–P27 et matrice complète sans nouveaux skips ; EVENT + complément Registration/composition ; architecture global ; audit diff et critères §10. Pas A3/A4/A5. |

Les probes EXPLAIN de A2-5 démontrent que le chemin est incrémental et indexable, pas une campagne
A5 de tuning ou qualification de capacité. Les commandes de test filtrées de mise au point peuvent
être ajoutées au rapport, mais ne remplacent pas les commandes canoniques de clôture.

## 10. Risques, arbitrages et critères de clôture

### 10.1 Arbitrages restant pour validation architecturale

| Arbitrage | Proposition / limite explicite |
|---|---|
| Modèle slot | Valider I avec namespace versionné, unique E sans partition et index de sélection ; sinon R avec analyse de compatibilité supplémentaire. Aucun retrait des quatre colonnes actuelles. |
| Garantie registre→slot | Codec/adapters de confiance + contrôles de forme/unique ; FK native des champs JSON non fournie. Si une garantie DB contre toute écriture SQL incohérente est exigée, choisir R ou un mécanisme supplémentaire avant code. |
| Arrêt | Valider FOR SHARE du registre jusqu'au commit, arrêt qui attend, et arrêt d'enrôlement distinct de l'exécution. FOR UPDATE du registre réservé à bootstrap/admin/réconciliation exceptionnelle, jamais prélock checkpoint normal. |
| UNKNOWN | Valider la barrière par flux, son coût exceptionnel et budgets timeout ; couverture durable suffit, pas d'audit « quel backend a gagné ». Journal de batch à ajouter uniquement si ce dernier besoin devient requis. |
| Hash UserCreated | Valider user_id, XOR UUID signé et colonne GENERATED STORED ; accepter une fenêtre ALTER avec réécriture potentielle ; aucune modification des hash existants. |
| Partition/règles | N et algorithme figés, cid neuf pour tout changement ; démarrage depuis zéro. Choisir valeurs opérationnelles N, limite k, délais/backoff avant implémentation/mesure, sans en faire des règles métier dans Discovery. |
| Placement et assemblage | Confirmer contrats dans engine-consumption (dépendances existantes), owner dans infra PRIMARY, composition passive explicite. Pas de nouveau module ni scheduler A4 dans A2. |
| Transition des anciens workers | Pas de conversion/fusion de slots ni de dispatch automatique des règles A1 ; bascule A3/A4 devra garantir idempotence des effets, fan-out et correction LKV. Préparer les manifests immuables réels avant activation des nouveaux consumers. |
| Déploiement | Rôles effectifs, schémas non-public, fenêtre index/hash, restore et réserves 4E restent à qualifier ; aucune certification de production par A2. |

Risques techniques à surveiller : amplification N×scan par génération (toutes les partitions
examinent le préfixe global), skew/collisions de hash, duplication d'index JSONB et WAL, attente
unicité/registry avant CAS, famine sous contenders, longue transaction bloquant l'arrêt,
privilèges SECURITY DEFINER et dérive de manifeste. Aucun de ces risques ne justifie de rouvrir
A1 ou d'implémenter A5 par anticipation ; mesure, bornes et résultat d'incident doivent être visibles.
Aucune impossibilité démontrée des décisions acquises n'a été identifiée par cette revue statique.

### 10.2 Critères précis de clôture fonctionnelle A2

1. Plan A2-P validé avec arbitrages ci-dessus, scope définitif consigné **avant** code.
2. Registre immuable et bootstrap idempotent de N checkpoints, lifecycle actif/inactif et protocole
   d'administration livrés, sans activation automatique de flux/E en production.
3. Hash UserCreated persisté dès écriture, historique couvert sans mutation des facts/positions,
   preuves de compatibilité avec le writer Registration et le binaire antérieur adapté à E.
4. Bijection slot A1 démontrée : un seul `(family,eventId,cid)` malgré contention/mauvaise partition,
   même ConsumptionKey en acquisition, sélection de partition indexable, anciennes clés intactes.
5. Les quatre requêtes seek exécutent une page globale contiguë bornée, détectent source/trou visibles,
   filtrent seulement par hash local, et ne lisent aucun payload ni anti-jointure historique.
6. INSERT idempotent et CAS atomiques ; perdant/erreur/kill précommit laisse zéro partiel ; DONE,
   Busy, retry et claims ne sont ni réinitialisés ni des barrières d'avancement Discovery.
7. COMMIT UNKNOWN réel conserve l'incertitude et se réconcilie durablement, y compris backend encore
   en cours ; arrêt concurrent prouvé jusqu'au commit. Aucune preuve limitée au mock transport.
8. Compatibilité Acquire/Execute/Finalize/retry/takeover/provenance actuelle sur slots enrôlés,
   régression non-E et retour dormant attestés, sans nouveau consumer UserCreated métier requis.
9. Matrice P01–P27 traçable à méthodes/tests/résultats, logs PostgreSQL et SHA ; aucun non-exécuté ou
   nouveau skip caché comme PASS ; EVENT, compléments déclarés et gate global requis exécutés.
10. Rapport final obligatoire : start HEAD, commit, impact prévu/observé, slices, commandes exactes,
    crossings, gate global/full et justification, DB CURRENT/baseline/historique, push/divergence,
    état Git et preuves. Runbook et Step Plan mis à jour alors selon état réel, pas clôture par intention.

**La clôture A2 n'est pas la clôture du chantier A** : A3 (reprise), A4 (orchestration/équité), A5
(optimisation/qualification), B/C READ et cleanup général de Consumption restent exclus. Les outcomes,
consumer_type/components et la provenance générique sont conservés pendant A2, conformément au
report après Discovery. Les garanties CURRENT de READ et les réserves production 4E ne sont pas
requalifiées par le plan.

## 11. Références vérifiables au SHA d'audit

Les lignes sont des repères de lecture, les symboles restent recherchables si les lignes évoluent.

- **S01** : [app/infra-persistence-primary-jpa/src/main/resources/db/migration/V2__projection_outbox_tasks.sql:1](../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V2__projection_outbox_tasks.sql#L1) — `create table business_event_outbox`.
- **S02** : [app/infra-persistence-primary-jpa/src/main/resources/db/migration/V15__canonical_business_event_types.sql:70](../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V15__canonical_business_event_types.sql#L70) — `add constraint ck_business_event_outbox_event_type`.
- **S03** : [app/infra-persistence-primary-jpa/src/main/resources/db/migration/V17__command_outcomes_and_terminal_events.sql:24](../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V17__command_outcomes_and_terminal_events.sql#L24) — `create table command_terminal_events`.
- **S04** : [app/infra-persistence-primary-jpa/src/main/resources/db/migration/V20__external_identity_binding_lifecycle.sql:16](../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V20__external_identity_binding_lifecycle.sql#L16) — `create table external_identity_binding_facts`.
- **S05** : [app/infra-persistence-primary-jpa/src/main/resources/db/migration/V22__repair_binding_history_and_contract.sql:32](../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V22__repair_binding_history_and_contract.sql#L32) — `hashtext` ; [app/infra-persistence-primary-jpa/src/main/resources/db/migration/V21__binding_occurrences_and_fact_expand.sql:13](../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V21__binding_occurrences_and_fact_expand.sql#L13) — `create table external_identity_binding_occurrences`.
- **S06** : [app/infra-persistence-primary-jpa/src/main/resources/db/migration/V23__close_binding_fact_type_contract.sql:7](../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V23__close_binding_fact_type_contract.sql#L7) — `fact_type`.
- **S07** : [app/infra-persistence-primary-jpa/src/main/resources/db/migration/V28__registration_outcomes.sql:17](../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V28__registration_outcomes.sql#L17) — `create table user_created_facts`.
- **S08** : [app/infra-persistence-primary-jpa/src/main/resources/db/migration/V4__consumption_engine.sql:1](../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V4__consumption_engine.sql#L1) — `create table consumption_slots`.
- **S09** : [app/infra-persistence-primary-jpa/src/main/resources/db/migration/V30__prepared_event_transactional_sequencing.sql:35](../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V30__prepared_event_transactional_sequencing.sql#L35) — `create table business_event_positions`.
- **S10** : [app/infra-persistence-primary-jpa/src/main/resources/db/migration/V31__event_sequencing_administration.sql:165](../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V31__event_sequencing_administration.sql#L165) — `create function activate_event_sequencing`.
- **S11** : [app/infra-persistence-primary-jpa/src/main/resources/db/migration/V6__consumption_terminal_reason.sql:1](../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V6__consumption_terminal_reason.sql#L1) — `alter table consumption_slots`.
- **S12** : [app/infra-persistence-primary-jpa/src/main/resources/db/migration/V7__consumption_processing_failure_code.sql:1](../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V7__consumption_processing_failure_code.sql#L1) — `alter table consumption_claims`.
- **S13** : [app/infra-persistence-primary-jpa/src/main/resources/db/migration/V13__canonical_projection_tasks.sql:6](../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V13__canonical_projection_tasks.sql#L6) — `create table projection_tasks`.
- **S14** : [app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/entity/outbox/JpaBusinessEventOutboxEntity.java:102](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/entity/outbox/JpaBusinessEventOutboxEntity.java#L102) — `this.potPartitionHash` ; [app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/outbox/JpaPotCommandEventAppendAdapter.java:64](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/outbox/JpaPotCommandEventAppendAdapter.java#L64) — `append` ; [app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/outbox/JpaBusinessEventOutboxAdapter.java:38](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/outbox/JpaBusinessEventOutboxAdapter.java#L38) — `append`.
- **S15** : [app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/entity/outbox/PotPartitioner.java:20](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/entity/outbox/PotPartitioner.java#L20) — `return potId.hashCode()`.
- **S16** : [app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/command/JdbcCommandOutcomeAdapter.java:54](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/command/JdbcCommandOutcomeAdapter.java#L54) — `outcome.commandId().value().hashCode()`.
- **S17** : [app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/identity/ExternalIdentityBindingFactJdbcRepository.java:21](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/identity/ExternalIdentityBindingFactJdbcRepository.java#L21) — `hashtext`.
- **S18** : [app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/JdbcUserCreatedFactAdapter.java:22](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/JdbcUserCreatedFactAdapter.java#L22) — `public EventRef append` ; [app/engine-consume-registration/src/main/java/com/kartaguez/pocoma/engine/consume/registration/UserCreatedFactPort.java:7](../../app/engine-consume-registration/src/main/java/com/kartaguez/pocoma/engine/consume/registration/UserCreatedFactPort.java#L7) — `interface UserCreatedFactPort`.
- **S19** : [app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/segmentation/PartitionHash.java:12](../../app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/segmentation/PartitionHash.java#L12) — `public static PartitionHash forPot` ; [app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/segmentation/WorkerSegment.java:23](../../app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/segmentation/WorkerSegment.java#L23) — `public boolean owns`.
- **S20** : [app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/processing/event/JdbcProjectionMaterializationDiscoveryAdapter.java:40](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/processing/event/JdbcProjectionMaterializationDiscoveryAdapter.java#L40) — `public List<ProjectionMaterializationCandidate> findCandidates` ; [app/engine-produce-projection-task/src/main/java/com/kartaguez/pocoma/engine/produce/projectiontask/port/ProjectionMaterializationDiscoveryPort.java:13](../../app/engine-produce-projection-task/src/main/java/com/kartaguez/pocoma/engine/produce/projectiontask/port/ProjectionMaterializationDiscoveryPort.java#L13) — `interface ProjectionMaterializationDiscoveryPort`.
- **S21** : [app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/command/JdbcCommandResultSource.java:27](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/command/JdbcCommandResultSource.java#L27) — `public Optional<Candidate> next` ; [app/engine-materialize-command-result/src/main/java/com/kartaguez/pocoma/engine/materialize/commandresult/CommandResultDiscovery.java:6](../../app/engine-materialize-command-result/src/main/java/com/kartaguez/pocoma/engine/materialize/commandresult/CommandResultDiscovery.java#L6) — `interface CommandResultDiscovery`.
- **S22** : [app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/identity/JdbcBindingFactDiscoveryAdapter.java:25](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/identity/JdbcBindingFactDiscoveryAdapter.java#L25) — `public Optional<BindingFactCandidate> findNextEligible` ; [app/supra-consume-binding/src/main/java/com/kartaguez/pocoma/supra/consume/binding/BindingFactConsumptionLocator.java:65](../../app/supra-consume-binding/src/main/java/com/kartaguez/pocoma/supra/consume/binding/BindingFactConsumptionLocator.java#L65) — `public static ConsumptionKey key` ; [app/engine-materialize-current-binding/src/main/java/com/kartaguez/pocoma/engine/materialize/currentbinding/BindingFactDiscoveryPort.java:6](../../app/engine-materialize-current-binding/src/main/java/com/kartaguez/pocoma/engine/materialize/currentbinding/BindingFactDiscoveryPort.java#L6) — `interface BindingFactDiscoveryPort`.
- **S23** : [app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/processing/event/JpaLatestKnownVersionEventDiscoveryAdapter.java:28](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/processing/event/JpaLatestKnownVersionEventDiscoveryAdapter.java#L28) — `public Optional<EventConsumptionCandidate> findNextEligibleCandidate` ; [app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/consumption/JpaLatestKnownVersionEventDiscoveryRepository.java:23](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/consumption/JpaLatestKnownVersionEventDiscoveryRepository.java#L23) — `public List<EventCandidateRow> findNextEligible` ; [app/supra-consume-lkv/src/main/java/com/kartaguez/pocoma/supra/consume/lkv/LatestKnownVersionConsumptionLocator.java:82](../../app/supra-consume-lkv/src/main/java/com/kartaguez/pocoma/supra/consume/lkv/LatestKnownVersionConsumptionLocator.java#L82) — `public static ConsumptionKey key` ; [app/supra-consume-lkv/src/main/java/com/kartaguez/pocoma/supra/consume/lkv/LatestKnownVersionEventDiscoveryPort.java:10](../../app/supra-consume-lkv/src/main/java/com/kartaguez/pocoma/supra/consume/lkv/LatestKnownVersionEventDiscoveryPort.java#L10) — `interface LatestKnownVersionEventDiscoveryPort`.
- **S24** : [app/supra-consume-event/src/main/java/com/kartaguez/pocoma/supra/consume/event/materialization/ProjectionMaterializationConsumptionKeys.java:19](../../app/supra-consume-event/src/main/java/com/kartaguez/pocoma/supra/consume/event/materialization/ProjectionMaterializationConsumptionKeys.java#L19) — `public static ConsumptionKey consumptionKey` ; [app/supra-consume-event/src/main/java/com/kartaguez/pocoma/supra/consume/event/materialization/ProjectionMaterializationConsumptionSource.java:38](../../app/supra-consume-event/src/main/java/com/kartaguez/pocoma/supra/consume/event/materialization/ProjectionMaterializationConsumptionSource.java#L38) — `public FencedConsumptionCandidateSearch`.
- **S25** : [app/supra-consume-event/src/main/java/com/kartaguez/pocoma/supra/consume/event/materialization/ProjectionMaterializationConsumptionService.java:29](../../app/supra-consume-event/src/main/java/com/kartaguez/pocoma/supra/consume/event/materialization/ProjectionMaterializationConsumptionService.java#L29) — `public FencedMutationResult finalize` ; [app/engine-produce-projection-task/src/main/java/com/kartaguez/pocoma/engine/produce/projectiontask/materialization/ProduceProjectionTaskService.java:10](../../app/engine-produce-projection-task/src/main/java/com/kartaguez/pocoma/engine/produce/projectiontask/materialization/ProduceProjectionTaskService.java#L10) — `class ProduceProjectionTaskService`.
- **S26** : [app/supra-consume-command-result/src/main/java/com/kartaguez/pocoma/supra/consume/commandresult/CommandResultConsumptionLocator.java:63](../../app/supra-consume-command-result/src/main/java/com/kartaguez/pocoma/supra/consume/commandresult/CommandResultConsumptionLocator.java#L63) — `public static ConsumptionKey key`.
- **S27** : [app/supra-consume-registration/src/main/java/com/kartaguez/pocoma/supra/consume/registration/RegistrationConsumptionLocator.java:68](../../app/supra-consume-registration/src/main/java/com/kartaguez/pocoma/supra/consume/registration/RegistrationConsumptionLocator.java#L68) — `public static ConsumptionKey key` ; [app/supra-consume-registration-result/src/main/java/com/kartaguez/pocoma/supra/consume/registrationresult/RegistrationResultConsumptionLocator.java:57](../../app/supra-consume-registration-result/src/main/java/com/kartaguez/pocoma/supra/consume/registrationresult/RegistrationResultConsumptionLocator.java#L57) — `public static ConsumptionKey key` ; [app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/JdbcRegistrationDiscovery.java:34](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/JdbcRegistrationDiscovery.java#L34) — `hashtext` ; [app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/JdbcRegistrationResultDiscovery.java:34](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/JdbcRegistrationResultDiscovery.java#L34) — `hashtext` ; [app/engine-materialize-registration-result/src/main/java/com/kartaguez/pocoma/engine/materialize/registrationresult/RegistrationResultDiscovery.java:6](../../app/engine-materialize-registration-result/src/main/java/com/kartaguez/pocoma/engine/materialize/registrationresult/RegistrationResultDiscovery.java#L6) — `CONSUMER_TYPE`.
- **S28** : [app/engine-consume-projection-task/src/main/java/com/kartaguez/pocoma/engine/consume/projectiontask/ProjectionTaskKeys.java:16](../../app/engine-consume-projection-task/src/main/java/com/kartaguez/pocoma/engine/consume/projectiontask/ProjectionTaskKeys.java#L16) — `public static ConsumptionKey consumptionKey` ; [app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/projection/JdbcProjectionTaskStoreAdapter.java:30](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/projection/JdbcProjectionTaskStoreAdapter.java#L30) — `class JdbcProjectionTaskStoreAdapter` ; [app/engine-consume-projection-task/src/main/java/com/kartaguez/pocoma/engine/consume/projectiontask/ProjectionTaskConsumptionService.java:25](../../app/engine-consume-projection-task/src/main/java/com/kartaguez/pocoma/engine/consume/projectiontask/ProjectionTaskConsumptionService.java#L25) — `class ProjectionTaskConsumptionService`.
- **S29** : [app/supra-consume-command/src/main/java/com/kartaguez/pocoma/supra/consume/command/CommandConsumptionKeys.java:21](../../app/supra-consume-command/src/main/java/com/kartaguez/pocoma/supra/consume/command/CommandConsumptionKeys.java#L21) — `public static ConsumptionKey forCommand` ; [app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/command/JpaCommandConsumptionDiscoveryAdapter.java:27](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/command/JpaCommandConsumptionDiscoveryAdapter.java#L27) — `public Optional<CommandConsumptionCandidate>` ; [app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/command/JpaCommandConsumptionDiscoveryRepository.java:16](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/command/JpaCommandConsumptionDiscoveryRepository.java#L16) — `class JpaCommandConsumptionDiscoveryRepository` ; [app/infra-persistence-primary-jpa/src/main/resources/db/migration/V24__contract_recorded_commands.sql:24](../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V24__contract_recorded_commands.sql#L24) — `consumption_slots`.
- **S30** : [app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/transaction/event/PrimaryEventTransactionRunner.java:45](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/transaction/event/PrimaryEventTransactionRunner.java#L45) — `@Override public <T> T runInTransaction` ; [app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/transaction/event/JdbcEventTransactionSequencer.java:47](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/transaction/event/JdbcEventTransactionSequencer.java#L47) — `@Override public void execute`.
- **S31** : [app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/consumption/JpaConsumptionSlotRepository.java:18](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/consumption/JpaConsumptionSlotRepository.java#L18) — `insert into consumption_slots`.
- **S32** : [app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/consumption/JpaConsumptionLifecycleAdapter.java:63](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/consumption/JpaConsumptionLifecycleAdapter.java#L63) — `public AcquireResult acquire` ; [app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/AcquireConsumptionService.java:13](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/AcquireConsumptionService.java#L13) — `class AcquireConsumptionService`.
- **S33** : [app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/FinalizeConsumptionService.java:43](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/FinalizeConsumptionService.java#L43) — `public FencedMutationResult finalizeConsumption` ; [app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/ExecuteConsumptionService.java:17](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/ExecuteConsumptionService.java#L17) — `class ExecuteConsumptionService` ; [app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/HandleConsumptionFailureService.java:21](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/HandleConsumptionFailureService.java#L21) — `class HandleConsumptionFailureService`.
- **S34** : [app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/consumption/JpaConsumptionProvenanceAdapter.java:21](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/consumption/JpaConsumptionProvenanceAdapter.java#L21) — `class JpaConsumptionProvenanceAdapter` ; [app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/port/out/consumption/ConsumptionProvenancePersistencePort.java:9](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/port/out/consumption/ConsumptionProvenancePersistencePort.java#L9) — `interface ConsumptionProvenancePersistencePort`.
- **S35** : [app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/transaction/consumption/TransactionalAcquireConsumptionUseCase.java:10](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/transaction/consumption/TransactionalAcquireConsumptionUseCase.java#L10) — `class TransactionalAcquireConsumptionUseCase` ; [app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/transaction/consumption/TransactionalExecuteConsumptionUseCase.java:11](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/transaction/consumption/TransactionalExecuteConsumptionUseCase.java#L11) — `class TransactionalExecuteConsumptionUseCase` ; [app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/transaction/consumption/TransactionalFinalizeConsumptionUseCase.java:13](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/transaction/consumption/TransactionalFinalizeConsumptionUseCase.java#L13) — `class TransactionalFinalizeConsumptionUseCase` ; [app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/transaction/consumption/TransactionalHandleConsumptionFailureUseCase.java:13](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/transaction/consumption/TransactionalHandleConsumptionFailureUseCase.java#L13) — `class TransactionalHandleConsumptionFailureUseCase` ; [app/orchestrator-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/consumption/SequentialConsumptionOrchestrator.java:29](../../app/orchestrator-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/consumption/SequentialConsumptionOrchestrator.java#L29) — `class SequentialConsumptionOrchestrator` ; [app/orchestrator-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/consumption/AcquireThenFinalizeConsumptionOrchestrator.java:22](../../app/orchestrator-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/consumption/AcquireThenFinalizeConsumptionOrchestrator.java#L22) — `class AcquireThenFinalizeConsumptionOrchestrator` ; [app/orchestrator-poll-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/poll/consumption/ConsumptionPollingWorker.java:17](../../app/orchestrator-poll-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/poll/consumption/ConsumptionPollingWorker.java#L17) — `class ConsumptionPollingWorker`.
- **S36** : [app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/ConsumptionOwnerTransactionRunner.java:27](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/ConsumptionOwnerTransactionRunner.java#L27) — `public <T> T runInTransaction` ; [app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/publication/PrimaryEventProtocol.java:14](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/publication/PrimaryEventProtocol.java#L14) — `public PrimaryEventProtocol` ; [app/infra-tx-spring/src/main/java/com/kartaguez/pocoma/infra/tx/spring/SpringTransactionRunnerConfiguration.java:16](../../app/infra-tx-spring/src/main/java/com/kartaguez/pocoma/infra/tx/spring/SpringTransactionRunnerConfiguration.java#L16) — `TransactionRunner transactionRunner` ; [app/infra-tx-spring/src/main/java/com/kartaguez/pocoma/infra/tx/spring/SpringTransactionRunner.java:21](../../app/infra-tx-spring/src/main/java/com/kartaguez/pocoma/infra/tx/spring/SpringTransactionRunner.java#L21) — `public <T> T runInTransaction`.
- **S37** : [app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/key/ConsumerIdentity.java:8](../../app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/key/ConsumerIdentity.java#L8) — `public record ConsumerIdentity` ; [app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/key/ConsumableIdentity.java:8](../../app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/key/ConsumableIdentity.java#L8) — `public record ConsumableIdentity` ; [app/engine-consumption/pom.xml:16](../../app/engine-consumption/pom.xml#L16) — `<dependencies>`.
- **S38** : [docs/operations/sql/event-consumption-preflight.sql:6](../operations/sql/event-consumption-preflight.sql#L6) — `consumption_slots` ; [docs/operations/sql/event-consumption-validate.sql:6](../operations/sql/event-consumption-validate.sql#L6) — `consumption_slots` ; [docs/operations/event-consumption-cutover.md:28](../operations/event-consumption-cutover.md#L28) — `## Procédure`.

[S01]: ../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V2__projection_outbox_tasks.sql#L1
[S02]: ../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V15__canonical_business_event_types.sql#L70
[S03]: ../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V17__command_outcomes_and_terminal_events.sql#L24
[S04]: ../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V20__external_identity_binding_lifecycle.sql#L16
[S05]: ../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V22__repair_binding_history_and_contract.sql#L32
[S06]: ../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V23__close_binding_fact_type_contract.sql#L7
[S07]: ../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V28__registration_outcomes.sql#L17
[S08]: ../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V4__consumption_engine.sql#L1
[S09]: ../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V30__prepared_event_transactional_sequencing.sql#L35
[S10]: ../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V31__event_sequencing_administration.sql#L165
[S11]: ../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V6__consumption_terminal_reason.sql#L1
[S12]: ../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V7__consumption_processing_failure_code.sql#L1
[S13]: ../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V13__canonical_projection_tasks.sql#L6
[S14]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/entity/outbox/JpaBusinessEventOutboxEntity.java#L102
[S15]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/entity/outbox/PotPartitioner.java#L20
[S16]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/command/JdbcCommandOutcomeAdapter.java#L54
[S17]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/identity/ExternalIdentityBindingFactJdbcRepository.java#L21
[S18]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/JdbcUserCreatedFactAdapter.java#L22
[S19]: ../../app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/segmentation/PartitionHash.java#L12
[S20]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/processing/event/JdbcProjectionMaterializationDiscoveryAdapter.java#L40
[S21]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/command/JdbcCommandResultSource.java#L27
[S22]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/identity/JdbcBindingFactDiscoveryAdapter.java#L25
[S23]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/processing/event/JpaLatestKnownVersionEventDiscoveryAdapter.java#L28
[S24]: ../../app/supra-consume-event/src/main/java/com/kartaguez/pocoma/supra/consume/event/materialization/ProjectionMaterializationConsumptionKeys.java#L19
[S25]: ../../app/supra-consume-event/src/main/java/com/kartaguez/pocoma/supra/consume/event/materialization/ProjectionMaterializationConsumptionService.java#L29
[S26]: ../../app/supra-consume-command-result/src/main/java/com/kartaguez/pocoma/supra/consume/commandresult/CommandResultConsumptionLocator.java#L63
[S27]: ../../app/supra-consume-registration/src/main/java/com/kartaguez/pocoma/supra/consume/registration/RegistrationConsumptionLocator.java#L68
[S28]: ../../app/engine-consume-projection-task/src/main/java/com/kartaguez/pocoma/engine/consume/projectiontask/ProjectionTaskKeys.java#L16
[S29]: ../../app/supra-consume-command/src/main/java/com/kartaguez/pocoma/supra/consume/command/CommandConsumptionKeys.java#L21
[S30]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/transaction/event/PrimaryEventTransactionRunner.java#L45
[S31]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/consumption/JpaConsumptionSlotRepository.java#L18
[S32]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/consumption/JpaConsumptionLifecycleAdapter.java#L63
[S33]: ../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/FinalizeConsumptionService.java#L43
[S34]: ../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/consumption/JpaConsumptionProvenanceAdapter.java#L21
[S35]: ../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/transaction/consumption/TransactionalAcquireConsumptionUseCase.java#L10
[S36]: ../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/ConsumptionOwnerTransactionRunner.java#L27
[S37]: ../../app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/key/ConsumerIdentity.java#L8
[S38]: ../operations/sql/event-consumption-preflight.sql#L6
