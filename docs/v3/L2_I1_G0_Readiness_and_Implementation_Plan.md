# Pocoma V3 — G0 et plan exécutable I1

**Date :** 2026-10-26. **Référence examinée :** `bc1f64b8fc7c83d485a5cc1aad6ac0210579da8e`, `feat/v3-architecture`. **Nature :** préparation documentaire, aucun code I1 ou I2 exécuté. Le présent mandat fournit le **GO humain de lancement** ; il ne convertit ni une autoévaluation en audit indépendant ni une méthode prévisionnelle en test passé.

## A. État Git et documentaire

Le dépôt `kartaguez/pocoma` a pour remote `https://github.com/kartaguez/pocoma.git`. `git ls-remote origin refs/heads/feat/v3-architecture` a renvoyé `bc1f64b8fc7c83d485a5cc1aad6ac0210579da8e`. Le worktree isolé `/private/tmp/pocoma-v3-postaud`, initialement à `055e34eb97286d020cc0ba2fa6fdaa50451b9a61` et en retard d'un commit, a été avancé **en fast-forward** à ce SHA ; divergence ensuite 0/0, état propre avant ce rapport. Le commit distant supplémentaire `bc1f64b8` retire le rapport d'audit Gemini trompeur et publie `L2_Independent_Counter_Audit_and_G0_Assessment.md`. Les commits précédents couvrent le catalogue d'oracles (`39aecafd`), corrections P0 (`e5ab9065`), audit post-consolidation (`1e5d2146`), nettoyage des prescriptions actives et consolidation R1–R10. Aucun ancien rapport supprimé n'est restauré.

Le checkout principal est `feat/runtime-monolith` à `90a3e9bc1a1fdd325d0c36e543824239cd85a431` et contient des changements préexistants (realm, README, Bruno, test PostgreSQL, documents d'architecture et `docs/v3/` non suivis). Il n'a été ni modifié ni nettoyé. Les worktrees V3 préexistants `c01-c02`, `cleanup`, `l0`, `l1-ab`, `l1-c`, `l1-consolidation`, `l1-de`, `l1-f2`, `l1-f2-before`, `l1-plan`, `n01`, `postaud` et `v3-l2-documentation`, ainsi que `debt-wa6-integration`, sont préservés. L'inventaire Git et la concordance distante devront être refaits juste avant I1.

Sources lues : [canon](V3_Canon_and_Roadmap.md), [plan directeur](V3_Implementation_Master_Plan.md), [plan L2](L2_Design_and_Verification_Plan.md), [registre A01–A12](L2_Decision_Register.md), [R1–R10](L2_R1_R10_Normative_Consolidation.md), [revue](L2_Contradictory_Review.md), [contre-audit](L2_Independent_Counter_Audit_and_G0_Assessment.md), [manifeste](L2_Certification_Coverage_Manifest.md), [oracles](L2_Independent_Test_Oracles.md), SPEC-01…10, [L1-C](L1_C_Transaction_Contract.md), [L1-F](L1_F_Closure_Report.md), [L1-F2](L1_F2_Closure_Report.md), `AGENTS.md` et [politique réacteur](../testing/Reactor_Verification_Policy.md). Le script `python3 docs/v3/check_l2_manifest.py` indique 253 identifiants normatifs, 380 lignes de preuve, 324 obligations L2, 56 reports, 33 oracles, 783 références, 87 sous-scénarios, sans erreur **mécanique**. Il ne vérifie ni la suffisance sémantique ni une exécution Java/PostgreSQL.

## B. Verdict G0 : préconditions distinctes

| Condition normative | Constat au SHA examiné | Classe / action minimale |
|---|---|---|
| Autorisation humaine d'ouvrir L2 | Donnée explicitement par le présent mandat. Les mentions « G0 NON VALIDÉ » des anciens plans décrivent l'état antérieur, pas un refus humain toujours actif. | **Satisfaite ;** inscrire ce GO dans le statut du plan directeur lors du prochain changement documentaire, sans annoncer L2 ACCEPTED. |
| A01–A12 et R1–R10, y compris A03 amendé à quatre tables, R1 avant suffixe et R3 SHA-256 | Les 22 décisions sont présentes et raccordées ; les anciennes options ouvertes sont supersédées. Les deux analyses indépendantes L2 ont conduit à la correction P0 et au contre-audit `bc1f64b8`. Le dernier conclut que le dossier est soumettable à G0 **avec réserves**, pas à un PASS technique. | **Satisfaite pour la conception ;** tenir les réserves dans I1–I5 et G1/G2. Pas de nouvel arbitrage conceptuel identifié. |
| Couverture et oracles P0 | Manifeste complet au contrôle statique, catalogue d'oracles et revue contradictoire documentés ; méthodes `L2ManifestProof` explicitement prévisionnelles. | **Satisfaite documentaire avec limite ;** geler les mappings avant campagne puis produire les vrais XML/SQL/traces après I1. |
| L1-F2 | `CLOSED/PASS interne`, index d'archives et mesures 100/1 000/10 000 disponibles ; gate obligatoire 107 méthodes PASS, gate global marqué INCONCLUSIVE à cause d'un skip exploratoire V2. Le contre-audit `bc1f64b8` refuse le « PASS indépendant » de l'ancien rapport retiré et demande une **revue indépendante bornée** des preuves, de leur portée et réserves. Aucun rapport courant ne conclut cette revue. | **Blocage G0 substantiel :** faire établir et publier une revue indépendante bornée L1-F2, sans exiger une nouvelle campagne complète par défaut. Un auditeur distinct vérifie archive/index/empreintes, code/DDL, variantes obligatoires, comparabilité des mesures, skip et limites, puis donne son verdict borné. Notre propre relecture n'est pas cet audit. |
| Paramètres bloquants du plan L2 §6 | Convention/vecteurs sont fixés. Aucun UUID littéral des neuf définitions, P final par famille, candidats et hypothèses de renouvellement n'était ratifié au SHA examiné ; `P=4/16` n'y est qu'illustratif. | **À figer avant I1/G0.** La configuration pilote explicite ci-dessous est une proposition technique révisable par la revue du plan, pas une valeur de production déjà approuvée. Son approbation et son enregistrement dans la composition/bootstrap lèvent ce point sans changer R3. |
| DDL, SQL de concurrence, code, tests PG, preuves de fencing et moteur | À produire en I1–I5 et qualifier en G1/G2. | **Travail I1 ou ultérieur**, pas preuve exigible à G0. |

**Verdict actuel : NO-GO pour exécuter I1.** Le GO humain est réel ; il manque la validation indépendante bornée L1-F2 et la fixation revue des paramètres pilote ci-dessous. Le traitement minimal est ces deux clôtures documentaires, puis une ligne de décision G0 datée dans le plan directeur. Les mentions de statut historiques peuvent alors être remplacées sans réécrire les rapports de preuve. Aucun besoin démontré de rouvrir A01–A12/R1–R10, de construire I2 avant I1 ou de rejouer le full reactor pour G0.

**Couverture des 22 arbitrages, sans transfert anticipé de travail :** A01/A03/A04 et R3/R7 forment le cœur I1 ; A02/A09 et R1/R9/R8 conditionnent la frontière réservée en I1 puis l'owner et la suppression en I2 ; A05/A08/A10 et R2/R5 passent au protocole de leases, claims et cession I3 ; A06/A07 et R4 sont Discovery I4 ; A11/R10 sont les boucles/équité I5 ; A12/R6 gouvernent pilotes, manifeste et gates I5–G3. Aucune de ces affectations ne marque une preuve future comme déjà acquise. R8 impose dès I1 l'absence de purge dans le DDL, les droits et l'inventaire des writers.

## C. Architecture cible I1 et paramètres à ratifier

I1 livre des **fondations inactives** : valeurs pures, routage, schémas, droits/bootstrap, garde de composition et inventaire des writers. Il ne livre ni acquisition, finalisation, Discovery, retry, boucle ou pilote Command actif. I2 garde l'owner physique/R1 ; I3 les leases/claims/cession ; I4 Discovery ; I5 les boucles et pilotes. Un appel direct de `ExecutePotCommand.execute` sous un Engine nouvellement ouvert serait une transaction imbriquée et est interdit.

### Responsabilités et types Java

| Couche / module | Types I1 proposés et responsabilité | Destination |
|---|---|---|
| `domain-consumption`, package `...domain.consumption.v3` | `ConsumableFamily` (sept valeurs exactes), `ConsumptionId(UUID)`, `ConsumableId(UUID)`, `ConsumptionDefinition(id,family,authority)`, `RoutingV1.hash/partition`. Valeurs immuables, validation pure, aucun Spring/JDBC. Le UUID natif porte l'identité ; le libellé Java ne sert pas de clé durable. | **ADAPT** le module ; **REUSE** la notion de clé, **REPLACE** `PartitionHash`/`WorkerSegment` historiques sur le chemin V3 (leur `UUID.hashCode`/segmentation n'est pas R3). |
| `engine-consumption`, packages `...engine.consumption.v3` et `.port` | `RoutingContract` et `RoutingContractPort.verify(definition)` ; `ConsumptionCatalog` injecté, vérifie unicité UUID, famille/autorité et P commun par famille. Contrats minimaux réservés à I2 : `ConsumptionParticipant`, `SlotCompletion` et `RecoveryObservation` (quatre issues), **sans implémenter le moteur**. Ne pas multiplier des interfaces relais. | **ADAPT/REPLACE** ancien cycle `Acquire/Execute/Finalize/HandleFailure` pour V3 ; ne pas le raccorder. |
| `infra-persistence-primary-jpa`, package `.v3.consumption` | `JdbcWriteRoutingContractAdapter` : lecture/validation du contrat WRITE dans la transaction du caller ; aucun `INSERT/UPDATE/DELETE` runtime sur ce contrat. `V3WriteBootstrap` est un outil administratif/script séparé, pas un bean au démarrage Worker. | **ADAPT** l'infrastructure ; `JdbcPotCommandStore` et `PotCommandJdbcTransactions` restent historiques jusqu'à I2, **non actifs** sur la composition pilote I1. |
| `infra-persistence-read-jdbc`, package `.v3.consumption` | `JdbcReadRoutingContractAdapter` analogue, lié uniquement à la datasource/manager READ ; schéma technique neuf. Aucun projecteur métier I1. | **ADAPT**. |
| `composition-runtime-spring`, package `.runtime.consumption.v3` | `V3ConsumptionCatalogConfiguration` explicite : neuf définitions littérales, P/familles/autorités, deux adaptateurs distincts ; `V3BootstrapGuard` vérifie cibles, contrat présent/compatible et interdit le démarrage de toute voie V3 si ancien writer/slot dédié est configuré. Pas d'auto-DDL Worker. | **ADAPT** ; `V3PotCommandComposition` existante reste **REUSE historique non activée**, sa conversion technique relève d'I2. |
| `port-transaction` / `infra-tx-spring` | Aucun nouveau manager ni transaction I1 ; I2 ajoutera l'owner physique explicite avec même connexion/XID par autorité. | **REUSE** en I1, **ADAPT éventuel I2** après preuve. |
| `engine-write-pot`, `domain-pot`, `domain-event` | Aucun changement métier I1. Préserver intention, Pot, événements denses, xid8 et protections C/F/F2. | **REUSE** ; adaptation du participant Pot en I2. |
| `orchestrator-consumption`, `orchestrator-poll-consumption`, `supra-consume-*`, runtimes EVENT/TASK, LKV, Binding autonome | Aucun chemin V3 actif I1 ; les chemins incompatibles sont **RETIRE de la composition pilote seulement**, retrait physique général L7. | **RETIRE du pilote / DEFER** pour la conversion prévue I3–I5. |

Les ports sont possédés par l'Engine, les adaptateurs par leur autorité physique, la composition injecte par constructeur sans scan implicite. `engine-consumption` ne dépend d'aucun type Pot/Identity/Projection. Les tables WRITE sont dans la même base/transaction que `v3_command`, Pot, résultats et événements ; les tables READ et les futurs effets techniques READ sont dans une **base PostgreSQL distincte**, avec datasource, pool et manager distincts. Les lectures de références WRITE par un Worker READ sont préparatoires ; aucun XA/2PC ni transaction physique entre les deux bases.

### Identités pilote proposées, à approuver comme contrat de composition

Les UUID ci-dessous sont des littéraux stables calculés une fois à partir de libellés de catalogue, puis **stockés tels quels** ; le runtime ne recalcule jamais UUIDv5 au démarrage. Ils ne sont ni alloués comme identités de production générale ni activés avant approbation du plan. `P=4` pour les sept familles du pilote, partagé par leurs Consumptions ; un Worker pilote candidat pour les partitions `{0,1,2,3}` de chaque définition. Une répartition multi-Worker peut avoir des candidats recouvrants, le lease restant arbitre. Toute modification de famille, UUID, version de hash ou P avec slots/K existants exige procédure de reconstruction/rejeu séparée.

| Consumption | UUID proposé | Famille / autorité |
|---|---|---|
| ExecuteCommand | `98f7268d-d340-581c-b83d-010926223e14` | COMMAND / WRITE |
| ExecuteIdentityRequest | `2999e75c-272d-5afe-a966-c9364ef021fe` | IDENTITY_REQUEST / WRITE, non activée I1 |
| MaterializePotVersion | `3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a` | POT_EVENT / READ |
| MaterializePotBalance | `190a93c6-b8af-5ac7-8001-72e2ec53c2df` | POT_EVENT / READ |
| MaterializePotVisibility | `b4d04cce-b5da-5d1b-9698-5ff9af9a46fa` | POT_EVENT / READ |
| MaterializeInvitation | `924cd9e3-435d-5804-9d1e-c6aecadb9ab4` | INVITATION_EVENT / READ |
| MaterializeCurrentBinding | `7b8d2838-bf7a-5117-868a-e5c14b4c0221` | IDENTITY_EVENT / READ |
| MaterializeCommandResult | `63664303-d33e-5d42-8cce-c4dae8023311` | COMMAND_COMPLETION_EVENT / READ |
| MaterializeIdentityRequestResult | `1d8ff246-358f-5ddd-a7f5-416efcfd7445` | IDENTITY_COMPLETION_EVENT / READ |

La définition technique READ de test aura son UUID distinct `c08982eb-e046-52d2-93f6-19c5eb5ed896`, famille POT_EVENT, uniquement dans le banc. Hypothèses **pilote**, à archiver avant I1 : `softCap(C,p)=64`, fenêtre `W=128` positions, lot `B=32`, `M_C=2`, `M_W=4`, `N=3`, `retryDelay=30 s`, lease partition `30 s`, claim `60 s`, renouvellement cible `10 s`, `lock_timeout=500 ms` pour les renouvellements, timeout transaction selon campagne. Réserver au moins **deux connexions PostgreSQL pour renouvellements de claims et une pour leases par autorité**, séparées du pool de traitements ; un renouvellement S1 bloqué doit laisser S2 progresser. Les durées/tailles sont des hypothèses de faisabilité pilote, pas des SLO ni des valeurs de production. En I1, les valider dans la configuration sans lancer les boucles ; les affiner avant P4/P7 sur mesures de temps DB. Les bornes de polling et du retour de curseur sont fixées avant I4/P7, pas nécessaires à l'exécution inactive d'I1.

### Routage SHA-256 v1

Octets exacts : ASCII `pocoma-consumable-v1`, `00`, famille ASCII canonique, `00`, 16 octets UUID réseau big-endian ; SHA-256 ; quatre premiers octets interprétés comme `int32` signé big-endian ; `partitionId=Math.floorMod(partitionHash,P)`. Aucun hash JVM, nom de classe, worker count ou timestamp. Les **21 vecteurs** de [L2 §6](L2_Design_and_Verification_Plan.md#6-identités-routage-leases-et-configuration-reproductible) sont l'oracle documentaire complet. Recalcul Python indépendant lors de cette revue : **21/21 égaux**. Vecteurs témoins : COMMAND/`123e4567-e89b-12d3-a456-426614174000` → digest `d00274f971e69d1a4d654f339962f999b9fa9fc80ae79417132d707dc68029cb`, hash `-805145351`, p4 `1`, p16 `9` ; POT_EVENT/même UUID → `-115927568`, p4 `0` ; COMMAND/UUID nul → `1843303294`, p4 `2` ; COMMAND/UUID maximal → `-1551802490`, p4 `2`. Les tests Java I1 comparent les 21 littéraux calculés hors production, y compris minimum int32 éventuel via test de `floorMod`, sans appeler `RoutingV1` pour produire l'attendu.

### DDL exact proposé pour **chacune** des deux autorités neuves

Exécuter ce bloc identique sous un `search_path` fixé sur le schéma V3 propre de la base concernée ; aucune FK inter-base. Le rôle propriétaire/admin exécute le DDL, le Worker ne possède pas le schéma. `partition_id` est une copie indexable de `floorMod(partition_hash,P)` : son contrôle par trigger est justifié par la sélection/mesure locale et évite un scan modulo non indexable. Aucun `family_id` dans le slot, DONE, UNKNOWN, workId, payload, compteur softCap ou journal de claims.

```sql
create table consumption_routing_contract (
    consumption_id uuid primary key,
    family text not null check (family in ('COMMAND','IDENTITY_REQUEST','POT_EVENT',
        'INVITATION_EVENT','IDENTITY_EVENT','COMMAND_COMPLETION_EVENT',
        'IDENTITY_COMPLETION_EVENT')),
    routing_version text not null check (routing_version = 'v1'),
    partition_count integer not null check (partition_count between 1 and 32768)
);

create table partition_ownership (
    consumption_id uuid not null references consumption_routing_contract(consumption_id),
    partition_id integer not null check (partition_id >= 0),
    owner_token uuid,
    owned_until timestamptz,
    primary key (consumption_id, partition_id),
    check ((owner_token is null) = (owned_until is null))
);

create table discovery_checkpoint (
    consumption_id uuid not null references consumption_routing_contract(consumption_id),
    partition_id integer not null check (partition_id >= 0),
    position bigint not null check (position >= 0),
    primary key (consumption_id, partition_id)
);

create table consumption_slot (
    consumption_id uuid not null references consumption_routing_contract(consumption_id),
    consumable_id uuid not null,
    partition_hash integer not null,
    partition_id integer not null check (partition_id >= 0),
    claim_token uuid,
    claimed_until timestamptz,
    retry_after timestamptz,
    attempt_count integer not null default 0 check (attempt_count >= 0),
    state text not null default 'AVAILABLE'
        check (state in ('AVAILABLE','QUARANTINED')),
    primary key (consumption_id, consumable_id),
    check ((claim_token is null) = (claimed_until is null)),
    check (state <> 'QUARANTINED' or
        (claim_token is null and claimed_until is null and retry_after is null))
);

create index consumption_slot_partition_walk_idx
    on consumption_slot (consumption_id, partition_id, consumable_id);
create index consumption_slot_live_count_idx
    on consumption_slot (consumption_id, partition_id)
    where state <> 'QUARANTINED';
create index consumption_slot_available_due_idx
    on consumption_slot (consumption_id, partition_id, retry_after, claimed_until)
    where state = 'AVAILABLE';
```

Les PK donnent unicité du slot, sérialisation de la ligne ownership et CAS du checkpoint. `partition_walk_idx` sert à la navigation de toutes les clés (y compris slots temporairement non sélectionnables), `live_count_idx` au compte réel R2 sous verrou ownership, `available_due_idx` aux candidats I3 ; mesurer `EXPLAIN (ANALYZE, BUFFERS)` avant de promettre son usage. Le FK empêche les orphelins ; **aucun `ON DELETE CASCADE`** n'autorise de purge. Le B-tree source WRITE additionnel est `create index v3_command_pending_request_idx on v3_command(request_id) where status='PENDING';` pour les pages keyset sans scan de TERMINAL ; la PK des événements/positions L1 sert à `(family,position)` et sera vérifiée, sans inventer un autre index avant mesure.

Le bloc de gardes suivant accompagne **à l'identique** les quatre tables WRITE et READ. Il rend la cohérence de famille/P et de partition vérifiable dans la base sans cinquième table. L'advisory lock ne sert qu'aux inserts administratifs du contrat ; une reconfiguration exceptionnelle exige un nouveau plan explicite.

```sql
create function consumption_routing_guard() returns trigger language plpgsql as $$
begin
    if tg_op <> 'INSERT' then
        raise exception 'Routing contract is immutable' using errcode = '23514';
    end if;
    perform pg_advisory_xact_lock(7442, hashtext(new.family));
    if exists (select 1 from consumption_routing_contract c
               where c.family = new.family
                 and (c.partition_count <> new.partition_count
                      or c.routing_version <> new.routing_version)) then
        raise exception 'Incompatible routing contract for family %', new.family
            using errcode = '23514';
    end if;
    return new;
end $$;
create trigger consumption_routing_contract_guard
    before insert or update or delete on consumption_routing_contract
    for each row execute function consumption_routing_guard();

create function consumption_partition_guard() returns trigger language plpgsql as $$
declare
    route_family text;
    p integer;
begin
    select family, partition_count into route_family, p
      from consumption_routing_contract where consumption_id = new.consumption_id;
    if not found then
        raise exception 'Missing routing contract' using errcode = '23503';
    end if;
    if new.partition_id < 0 or new.partition_id >= p then
        raise exception 'Partition outside routing contract' using errcode = '23514';
    end if;
    if tg_table_name = 'consumption_slot' then
        if new.partition_id <> ((new.partition_hash::bigint % p + p) % p) then
            raise exception 'Slot routing mismatch' using errcode = '23514';
        end if;
        if tg_op = 'UPDATE' then
            if (new.consumption_id, new.consumable_id, new.partition_hash, new.partition_id)
               is distinct from
               (old.consumption_id, old.consumable_id, old.partition_hash, old.partition_id) then
                raise exception 'Slot identity/routing immutable' using errcode = '23514';
            end if;
        end if;
    elsif tg_table_name = 'discovery_checkpoint' then
        if route_family in ('COMMAND', 'IDENTITY_REQUEST') then
            raise exception 'Burnable checkpoint forbidden' using errcode = '23514';
        end if;
        if tg_op = 'UPDATE' then
            if (new.consumption_id, new.partition_id) is distinct from
               (old.consumption_id, old.partition_id) or new.position < old.position then
                raise exception 'Checkpoint identity/position regression' using errcode = '23514';
            end if;
        end if;
    elsif tg_op = 'UPDATE' then
        if (new.consumption_id, new.partition_id) is distinct from
           (old.consumption_id, old.partition_id) then
            raise exception 'Ownership key immutable' using errcode = '23514';
        end if;
    end if;
    return new;
end $$;
create trigger consumption_slot_partition_guard
    before insert or update on consumption_slot
    for each row execute function consumption_partition_guard();
create trigger partition_ownership_partition_guard
    before insert or update on partition_ownership
    for each row execute function consumption_partition_guard();
create trigger discovery_checkpoint_partition_guard
    before insert or update on discovery_checkpoint
    for each row execute function consumption_partition_guard();
```

Le contrôle de `partition_hash` emploie `bigint`, y compris pour le minimum int32. Le `SELECT` du trigger est sûr car le contrat existant est immutable ; acquisition et renouvellement de lease vérifieront tout de même le contrat dans **leur** transaction en I3. Un contrôle SQL indépendant du catalogue doit confirmer ces définitions exactes et les deux migrations identiques ; **aucun INSERT de slot par le runtime I1**.

### Bootstrap administratif et ancien protocole

Créer deux bases/schémas V3 **neufs et identifiés** (`WRITE` et `READ`) après inventaire des noms de base, rôles, processus et données existantes ; refuser toute cible non vide ou inconnue. Le bootstrap WRITE applique, dans son schéma isolé, les migrations V3 Pot V1/V3/V4 et exécution V2 puis la migration de fondation V5 I1 ; READ applique uniquement son chemin V3 technique V1 I1. Ne pas exécuter une migration historique V2 ni effacer un schéma existant. La migration WRITE V5 vérifie `v3_command_slot` vide, crée les quatre tables, remplace **dans le schéma neuf seulement** `v3_require_command_unit()` afin que TERMINAL exige absence de `consumption_slot` pour l'UUID ExecuteCommand, puis supprime `v3_command_slot`. Sa queue SQL exacte, après le bloc commun et l'index PENDING, est :

```sql
do $$
begin
    if exists (select 1 from v3_command_slot) then
        raise exception 'Refusing nonempty legacy command slot cutover'
            using errcode = '23514';
    end if;
end $$;
create or replace function v3_require_command_unit() returns trigger language plpgsql as $$
declare c v3_command%rowtype; r v3_command_result%rowtype;
begin
    select * into c from v3_command where request_id = new.request_id;
    select * into r from v3_command_result where request_id = c.request_id;
    if c.status = 'PENDING' then
        if r.request_id is not null then
            raise exception 'Pending Command has result' using errcode = '23514';
        end if;
        return new;
    end if;
    if r.request_id is null
       or exists (select 1 from consumption_slot s
                  where s.consumption_id = '98f7268d-d340-581c-b83d-010926223e14'::uuid
                    and s.consumable_id = c.request_id)
       or not exists (select 1 from v3_command_completion_event e
                      join v3_event_position p on p.event_id = e.event_id
                       and p.family = 'COMMAND_COMPLETION'
                      where e.subject_id = c.request_id) then
        raise exception 'Incomplete terminal Command unit' using errcode = '23514';
    end if;
    if r.outcome = 'CHANGED' then
        if not exists (select 1 from v3_pot_event e
                       join v3_event_position p on p.event_id = e.event_id
                        and p.family = 'POT'
                       where e.request_id = c.request_id and e.pot_id = c.pot_id
                         and e.subject_version = r.resulting_pot_version)
           or not exists (select 1 from v3_pot_event_component x
                          join v3_pot_event e using (event_id)
                          where e.request_id = c.request_id) then
            raise exception 'Changed Command missing exact publication'
                using errcode = '23514';
        end if;
    elsif exists (select 1 from v3_pot_event where request_id = c.request_id) then
        raise exception 'No-effect Command has mutation event' using errcode = '23514';
    end if;
    return new;
end $$;
drop table v3_command_slot;
```

Conserver les triggers différés `v3_command_unit`, `v3_result_unit`, `v3_pot_event_request`, les 22 contraintes métier et la publication dense ; la preuve de l'ordre R1 complet attend I2. Les tests historiques L1 continuent sur leurs locations V1–V4/V2 sans V5, sans prétendre tester le nouveau chemin. **Si l'UUID ExecuteCommand change à la revue**, adapter ensemble le littéral du trigger, le bootstrap, la composition et ses oracles avant tout code ; ne pas permettre deux valeurs.

Sous le rôle admin, dans **une transaction par autorité** : vérifier l'empreinte/cible V3 vide, verrouiller `consumption_routing_contract` contre deux administrateurs, insérer les définitions WRITE ou READ ci-dessus avec `routing_version='v1'`, `partition_count=4`, valider que toutes les lignes d'une famille ont même P, puis précréer `partition_ownership(C,p)` pour p=0…3 avec token/échéance NULL ; ne créer aucun checkpoint burnable ni slot. Le script psql prévu reçoit **une seule** variable `authority` valant `WRITE` ou `READ`, fournie explicitement après le contrôle de cible. Son corps d'insertion est :

```sql
begin;
lock table consumption_routing_contract in share row exclusive mode;
with definitions(id, family, authority) as (values
 ('98f7268d-d340-581c-b83d-010926223e14'::uuid,'COMMAND','WRITE'),
 ('2999e75c-272d-5afe-a966-c9364ef021fe'::uuid,'IDENTITY_REQUEST','WRITE'),
 ('3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a'::uuid,'POT_EVENT','READ'),
 ('190a93c6-b8af-5ac7-8001-72e2ec53c2df'::uuid,'POT_EVENT','READ'),
 ('b4d04cce-b5da-5d1b-9698-5ff9af9a46fa'::uuid,'POT_EVENT','READ'),
 ('924cd9e3-435d-5804-9d1e-c6aecadb9ab4'::uuid,'INVITATION_EVENT','READ'),
 ('7b8d2838-bf7a-5117-868a-e5c14b4c0221'::uuid,'IDENTITY_EVENT','READ'),
 ('63664303-d33e-5d42-8cce-c4dae8023311'::uuid,'COMMAND_COMPLETION_EVENT','READ'),
 ('1d8ff246-358f-5ddd-a7f5-416efcfd7445'::uuid,'IDENTITY_COMPLETION_EVENT','READ')
)
insert into consumption_routing_contract(consumption_id,family,routing_version,partition_count)
select id,family,'v1',4 from definitions where authority = :'authority';
insert into partition_ownership(consumption_id,partition_id)
select c.consumption_id,p.partition_id
from consumption_routing_contract c
cross join generate_series(0,3) as p(partition_id);
do $$
begin
    if exists (select 1 from consumption_routing_contract
               group by family having count(distinct partition_count) <> 1)
       or exists (select 1 from consumption_slot)
       or exists (select 1 from discovery_checkpoint) then
        raise exception 'Invalid fresh routing bootstrap' using errcode='23514';
    end if;
end $$;
commit;
```

Le script vérifie **avant `BEGIN`** que `:'authority'` est dans `{WRITE,READ}`, que `current_database()`/`current_schema()`/rôle égalent l'inventaire signé, que les tables de routage sont vides et que le nombre de lignes attendu vaut 2/7 ; il refuse toute autre valeur ou réexécution. Cette vérification doit être codée, pas laissée à une lecture humaine du commentaire. `psql -v ON_ERROR_STOP=1` et transaction atomique sont obligatoires ; la création des migrations Flyway précède ce script. Le banc READ reçoit sa propre ligne **uniquement dans son environnement de test**, sans le mélanger aux neuf définitions métier de référence. Archiver DB/schema/role, UUID, SQL/checksum Flyway, hash de convention, vecteurs, grants et snapshots de catalogue. Redémarrer le Worker vérifie mais ne réexécute pas le bootstrap administratif et n'efface rien.

Rôle runtime : `USAGE` du schéma, `SELECT` seul sur `consumption_routing_contract` ; aucun droit CREATE/ALTER/DROP/TRUNCATE ni écriture du contrat. En I1, ne donner que les droits de lecture utiles sur ownership/K/slots ; I2–I4 accordent les DML minimaux à leurs writers après tests de non-purge. Le rôle admin distinct est seul propriétaire des tables et du contrat. Toute route manquante/incompatible empêche activation, Discovery et acquisition ; I3 vérifiera la ligne du contrat dans l'unité d'acquisition/renouvellement de lease, avec l'immuabilité SQL rendant les lectures sûres face à l'administration.

Inventorier par `rg` et graphe réel tous les usages `v3_command_slot`, `ExecutePotCommand`, `PotCommandJdbcTransactions`, `JdbcPotCommandStore`, `ConsumptionLifecyclePersistencePort`, `JpaConsumption*`, profils Spring, imports, auto-configurations, Docker/entrypoints et tests. Dans la **composition pilote V3** I1, refuser au démarrage les anciens beans/Workers/writers qui utilisent le slot dédié ou les anciennes tables de Consumption ; aucune seconde voie Command ou Task/LKV/Binding autonome n'est branchée à ces bases. Ne pas retirer physiquement le runtime historique ni ses données : autres environnements et tests L1 restent intacts. La conversion du participant et la suppression atomique réelle ne commencent qu'en I2.

### Ordres de verrous et entrelacements à fixer dès I1

I1 matérialise seulement les protections de **bootstrap/contrat**, mais son DDL et ses interfaces réservent le protocole suivant sans laisser de choix à improviser en I2–I4 :

| Opération future | Ordre et heure | États durables autorisés / preuve PostgreSQL |
|---|---|---|
| Bootstrap concurrent / lease | Admin sérialise les inserts par famille et sa transaction entière ; route immuable ensuite. Lease lit/valide contrat dans sa transaction, puis verrouille `partition_ownership(C,p)` et prend `clock_timestamp()` **après attente**. | Avant bootstrap aucun lease ; après, exactement une définition/P cohérents et au plus un token de lease valide. I1 : deux admins P différents, Worker sans droits, route absente. I3 : lease face à admin et mismatch. |
| Deux Discoveries du même `(C,p)` | Verrou ownership → heure DB/token → compte non-QUARANTINED → K si non-burnable → slots nouveaux → K. Pas de prise de verrou ownership après slot. | Aucun demi-enrôlement : K et slots tous deux anciens ou tous deux nouveaux ; I4/P2 avec barrières. |
| Burnable contre Finalizer | Discovery : ownership → source PENDING verrouillée → lecture MVCC **sans attente** du slot exact ; si slot présent, même QUARANTINED, abandon ; sinon INSERT sous source toujours protégée. Finalizer : slot → source → Identity → Pot → effets/résultat → `complete()` → suffixe. Il ne reprend jamais ownership. | PENDING+slot unique ou TERMINAL+effets/suffixe et aucun slot. Pas de slot TERMINAL ni cycle source↔slot. I4/P3-P5 : trois ordres forcés, `pg_blocking_pids`, SQLSTATE `40P01`, états après commit. |
| Finalizer, renouvellement, takeover | Finalizer verrouille slot et lit **une nouvelle** heure DB après attente ; token valide à ce point, verrou conservé jusqu'à résolution physique même si échéance passe. Renouvellement/takeover sur slot seul avec token/échéance CAS ; pas d'ownership final. | Un seul effet/suppression, ancien token inopérant ; expiration avant verrou refuse, après verrou permet. I3/I2 P4-P5 : barrières, PID/XID et snapshot extérieur. |
| Cession et réactivation | Après rollback **confirmé** seulement, Engine reprend slot→heure DB→token CAS ; admin CAS `QUARANTINED→AVAILABLE` sur slot exact, sans ownership ni softCap. | Count +1 au plus une fois ou aucun changement si claim perdu/UNKNOWN ; réactivation unique, slot conservé. I3/P6. |

L'analyse statique du DDL L1 a identifié `v3_require_command_unit()` dépendant de `v3_command_slot`, sans FK entrant vers ce slot ; elle ne prouve pas les entrelacements réels. Aucun deadlock déterministe n'a été observé par l'audit disponible. Le choix « doublon visible ⇒ abandon sans attendre » évite l'attente AB-BA sous verrou de source ; une insertion concurrente sur clé unique reste revalidée et qualifiée au test. Ne jamais substituer un `INSERT ON CONFLICT` aveugle ou une suppression du slot après le suffixe.

## D. Ordre d'implémentation fichier par fichier **après levée G0**

| Ordre | Fichier(s) à créer/modifier | Dépendance et sortie locale |
|---|---|---|
| 0 | `docs/v3/V3_Implementation_Master_Plan.md`, décision G0 datée ; présent plan ; manifeste campagne I1 | Inscrire GO humain, audit L1-F2 distinct et valeurs ratifiées ; conserver les rapports historiques. Sans ces pièces, arrêter. |
| 1 | `app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/v3/{ConsumableFamily,ConsumptionId,ConsumableId,ConsumptionDefinition,RoutingV1}.java` ; tests `.../v3/RoutingV1Test.java` | Sept familles, neuf définitions validables, 21 vecteurs externes, `floorMod` signé ; aucune dépendance technique. |
| 2 | `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/{ConsumptionCatalog,RoutingContract}.java`, `.../v3/port/RoutingContractPort.java` ; tests catalogue | Unicité UUID, P commun par famille, autorités et contrat manquant/incompatible refusés ; contrats minimaux de participation I2 sans implémentation prématurée. |
| 3 | `app/infra-persistence-primary-jpa/src/main/resources/db/v3-consumption-write/migration/V5__consumption_foundation.sql` et `app/infra-persistence-read-jdbc/src/main/resources/db/v3-consumption-read/migration/V1__consumption_foundation.sql` | Quatre tables et gardes identiques ; V5 WRITE retire le slot L1 après vérification vide, adapte la contrainte différée et indexe PENDING. Deux chemins Flyway explicitement distincts. Aucun ancien fichier de migration édité. |
| 4 | `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/consumption/JdbcWriteRoutingContractAdapter.java`, `app/infra-persistence-read-jdbc/src/main/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/v3/consumption/JdbcReadRoutingContractAdapter.java` ; POM READ si dépendance `engine-consumption` nécessaire | SELECT/compatibilité dans chaque autorité, aucun write de contrat par Worker ; vérifier absence de cycle Maven avant ajout de l'arête. |
| 5 | `scripts/v3/bootstrap-l2-routing.sql` et catalogue de configuration sous `docs/v3/` ; `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/{V3ConsumptionCatalogConfiguration,V3BootstrapGuard}.java` | Bootstrap admin explicite et une composition pilote inerte ; deux datasources/managers et pools distincts, aucune activation historique. Les neuf UUID et P du tableau deviennent littéraux après revue. |
| 6 | `app/infra-persistence-primary-jpa/src/test/java/.../v3/consumption/ConsumptionFoundationPostgresTest.java`, `app/infra-persistence-read-jdbc/src/test/java/.../v3/consumption/ReadConsumptionFoundationPostgresTest.java`, `app/runtime-command-consumption-worker/src/test/java/.../v3/ConsumptionBootstrapPostgresTest.java`, tests locaux de composition ; garde ArchUnit dans `app/architecture-tests` si frontière/POM changé | Catalogue SQL, triggers/grants, bootstrap/restart/isolation, deux admins, PENDING/TERMINAL et absence du slot dédié ; ancien handoff non actif. Les tests L1 historiques restent sur leurs locations. |
| 7 | Inventaire signé des writers/imports/profils et protocole de passage I2 dans `docs/v3/` ; provenance P1 | Vérifier chaque chemin contre les deux bases et le DDL. I2 reçoit ports/guard/owner sans voie de suppression parallèle. |

Les noms de classe sont proposés pour I1 et fixés par la revue de ce plan ; déplacer le contrat physique en Pot ou créer un deuxième moteur serait un changement architectural à soumettre à nouveau. Ne pas modifier `JdbcPotCommandStore` pour lui faire écrire le slot canonique avant I2 : cela créerait deux owners sans preuve R1.

## E. Tests, oracles et vérification

| Preuve I1 | IDs du manifeste et oracle | Observation indépendante / critère de sortie |
|---|---|---|
| Routage pur et catalogue | `L2M-R3-01…10`, `OR-AR-ROUTE` ; `E03-A`, `E03-H/I` selon portée | 21 vecteurs littéraux Python vs Java, mapping neuf UUID/famille/autorité, P=4 cohérent et mismatch refusé ; test de `Integer.MIN_VALUE` pour `floorMod`. Aucun PASS de transformations métier. |
| DDL, contraintes, index, droits | `L2M-R3-03/04/07/08`, `L2M-R7-02/07`, `OR-AR-ROUTE`, `OR-AR-NOPURGE` | `pg_catalog` externe : exactement quatre tables techniques par base, PK/FK/CHECK/triggers/index attendus ; deux admin bootstrap concurrents ne créent pas P divergents ; Worker ne peut écrire le contrat/DDL/truncate ; checkpoint burnable et partition invalide refusés. EXPLAIN PENDING avec population TERMINAL suffisante et buffers/plan archivés, sans imposer l'index au planner. |
| Bootstrap et isolement | `L2M-R7-01…09`, `OR-AR-BOOT`, `C06-A`, `OR-AR-BOUND` | Catalogues et snapshots avant/après des deux bases neuves et des bases existantes ; PENDING sans slot conservée, TERMINAL/résultat inchangés ; restart sans DDL ; mauvaise cible refusée ; aucun ancien writer actif. `C06-A` reste contribution structurelle I1, preuve d'unité physique à I2. |
| Garde terminale WRITE | `R1-10`, `R7-05`, `OR-AT-UNIT`, `OR-AR-NOPURGE` | Transaction SQL fixture : TERMINAL sans résultat/complétion ou avec slot canonique échoue au commit ; unité complète sans slot passe ; anciennes tables absentes dans schéma neuf ; aucune mutation métier réelle attribuée à I1. |
| Frontières de module/composition | `R1-01/09`, `R9-08`, `R7-05`, `OR-AR-BOUND` | Graphe/ArchUnit : Engine sans Pot/JDBC/Spring ; ports injectés, un seul mapping de datasource par autorité, ancienne voie inactive. Le test d'identité réelle XID/JPA/JDBC est prévu I2. |

Les entrelacements Discovery/Finalizer, expiration post-verrou, cession, ACK perdu, renouvellement S1/S2 et effet READ ne sont **pas** des critères de sortie I1 ; ils sont explicitement affectés à I2–I5/P2–P7 avec `OR-B-SET`, `OR-NB-SET`, `OR-CL-FENCE`, `OR-CL-RENEW`, `OR-AT-UNIT`, `OR-RC-FOUR`. Un test séquentiel ou un sleep ne les qualifiera pas. À chaque campagne, tables attendues et SQL de lecture externe sont établis avant l'exécution ; archiver XML, barrières, `pg_locks`/`pg_blocking_pids`, PID/XID, DB-now, états avant/après, commandes, SHA et skips. Les méthodes du manifeste restent INCONCLUSIVE tant qu'elles ne sont pas raccordées à ces preuves fraîches.

### Déclaration de tranche prospective I1 (politique du réacteur)

Impact production attendu : domaine/Engine de routage, adaptateurs techniques WRITE/READ et composition pilote inactive ; une arête POM READ→Engine si nécessaire. **Primaire COMMAND** pour le chemin WRITE et sa composition. **Secondaires : NONE** tant qu'aucun comportement EVENT/PROJECTION runtime n'est modifié ; le module READ reçoit en plus sa preuve directe. Impact interdit : WEB/BINDING/LKV, Identity réelle, projections métier, moteur I2/I3/I4/I5, API et nettoyage L7. Si une production EVENT/PROJECTION ou autre capacité change, déclarer la nouvelle slice **avant** modification et utiliser son ancre canonique. `FOUNDATION` seule ne prouve pas ces changements. DB : **CURRENT V3 neuf WRITE/READ**, migrations L1 V3 nécessaires et fondations I1, aucune chaîne V2 historique séparée ; documenter les anciennes migrations éventuellement lancées transitivement par Maven.

Commandes canoniques prévues depuis `app/`, **non exécutées maintenant** :

```bash
./mvnw -pl runtime-command-consumption-worker -am test
./mvnw -pl infra-persistence-read-jdbc -am test
./mvnw -pl architecture-tests -am test
```

La seconde commande est une preuve directe complémentaire, **pas** une slice canonique inventée. Le gate global `architecture-tests` est **requis à I1 si** l'arête POM ou les règles de frontière/isolation changent, ce qui est prévu ; sinon expliquer pourquoi l'hypothèse de graphe a été évitée et garder une preuve locale. Le full reactor `./mvnw test` est **non requis à I1** si l'impact reste borné ; il reste requis à G3/clôture majeure L2, et plus tôt seulement si restructuration large ou impact non bornable démontré. Les futurs I2–I5 déclareront leurs propres slices, normalement COMMAND puis EVENT/PROJECTION lorsque leurs runtimes sont réellement raccordés. Aucun `-amd` ni architecture-tests automatique hors condition normative.

**Sortie I1 vérifiable :** quatre tables et index/gardes exacts sur deux autorités neuves ; rôle Worker incapable de modifier le contrat ; neuf définitions stables et routage 21/21 ; bootstrap/restart idempotents et isolés ; aucune donnée préexistante touchée ; aucun ancien slot/writer dans le pilote ; tests PostgreSQL/catalogue/composition réellement exécutés et archivés ; architecture gate si POM/frontière changé ; écarts de tranche explicités ; aucun moteur ou PASS L2 global revendiqué. I2 commence seulement après revue de ces preuves et du handoff de l'owner physique.

## F. Risques et arbitrages strictement nécessaires

1. **Audit L1-F2 absent au niveau requis :** seule décision de G0 réellement bloquée à ce jour. L'auditeur doit borner son jugement au WRITE interne ; le skip exploratoire et l'absence de benchmark universel restent visibles. Pas de substitution par notre propre contrôle.
2. **Paramètres pilote non ratifiés au SHA initial :** la table UUID/P/hypothèses ci-dessus les rend révisables concrètement ; leur approbation avant I1 est une fixation de configuration R3, pas un nouvel arbitrage conceptuel. Les valeurs de production L8 ne sont pas décidées.
3. **DDL V5 et tests L1 :** la migration I1 n'est appliquée qu'à une cible neuve explicitement inspectée. Les anciennes locations de test continuent de tester l'ancien handoff historique ; aucune compatibilité implicite avec des données L1 n'est promise. Une cible non vide exige un mandat de migration séparé.
4. **R1/trigger et locks :** I1 adapte le catalogue et interdit l'ancienne voie ; I2 doit prouver suppression avant suffixe, contraintes différées et poisoning sous manager physique commun. I4 doit prouver le croisement source/slot par barrières. Un constat de cycle réel imposerait correction du SQL/ordre, pas un changement tacite de R1.
5. **Index/coût :** index PENDING choisi, mais son efficacité dépend du volume/planner ; mesurer avec TERMINAL nombreux. Le count softCap reste réel sous verrou ; aucune revendication de complexité constante ni de SLO PERF-01 I1.

## G. Recommandation et compte rendu du présent mandat

**NO-GO conditionnel pour lancer le code I1 maintenant ; GO pour la préparation et la revue du présent plan.** Lever d'abord (a) la revue indépendante bornée L1-F2, (b) la validation des UUID/P/hypothèses pilote et (c) l'enregistrement du GO humain et du franchissement G0 dans le plan directeur. Une fois ces pièces publiées et sans constat contradictoire ouvert, exécuter I1 exactement dans le périmètre ci-dessus ; arrêter avant I2 pour la revue prévue.

Tranche déclarée pour **ce rapport** : NONE, documentation seulement. Commandes exécutées : `git status`, `git rev-parse`, `git branch -vv`, `git worktree list`, `git log`, `git ls-remote`, `git pull --ff-only` sur le worktree isolé, recherches/lectures statiques `rg`/`cat`/`sed`, `python3 docs/v3/check_l2_manifest.py`, recalcul Python indépendant des 21 vecteurs et contrôles documentaires de livraison. Aucun Maven, PostgreSQL, migration ou code applicatif I1 exécuté. Franchissement de slice inattendu : **aucun**. Gate global : **non requis pour cette documentation**. Full reactor : **non exécuté, non requis**. Le rapport d'implémentation I1 futur devra enregistrer HEAD de départ, commit final, impacts attendus/réels, commandes effectives, gates, DB, éventuels franchissements, push et divergence finale conformément à la politique.
