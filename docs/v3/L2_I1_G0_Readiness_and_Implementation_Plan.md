# Pocoma V3 — L2 G0 : plan I1 amendé

**26 octobre 2026 — plan approuvé et dossier de réalisation I1 ; I2 non engagé.** Référence historique du premier plan : `f528586c94ee7e1b0574e9ff9b108781b0e8090f`. Avant ce changement, `HEAD` local de `feat/v3-architecture`, `origin/feat/v3-architecture` et `git ls-remote --heads origin feat/v3-architecture` donnaient tous ce SHA (divergence 0/0). Le worktree de rédaction `/private/tmp/pocoma-v3-l2-i1-amend` a été créé propre et détaché à ce SHA. Le checkout principal `feat/runtime-monolith` à `90a3e9bc` est sale ; tous ses travaux, ainsi que les worktrees existants, sont préservés. Recontrôler le remote avant le push puis avant l'exécution I1.

**Autorités :** [SPEC-03](SPEC-03_Events_Requests_Consumables.md), [SPEC-05](SPEC-05_Consumption_Discovery.md), [SPEC-10](SPEC-10_Test_Certification.md), [plan L2](L2_Design_and_Verification_Plan.md), [A01–A12](L2_Decision_Register.md), [R1–R10](L2_R1_R10_Normative_Consolidation.md), [manifeste](L2_Certification_Coverage_Manifest.md), [oracles](L2_Independent_Test_Oracles.md), [contrat L1-C](L1_C_Transaction_Contract.md), [matrice F2](L1_F2_Invariant_Matrix.md), `AGENTS.md` et [politique du réacteur](../testing/Reactor_Verification_Policy.md). Les signatures du plan L2 sont conceptuelles, pas des classes Java imposées. Les décisions A/R restent closes. Les mentions anciennes « G0 non validé » sont des instantanés historiques ; le [plan directeur](V3_Implementation_Master_Plan.md) porte le statut courant.

## Décision courante — G0 franchi le 26 octobre 2026

Le responsable autorise explicitement G0 → I1 après examen du plan amendé et de sa revue. Il lève la réserve procédurale relative à la recevabilité de la validation indépendante L1-F2 et accepte les preuves existantes avec leurs limites documentées (portée WRITE bornée, skip exploratoire V2, absence de garantie générale O(1)). L’audit historique a été réalisé puis contesté ; cette dérogation ne réécrit aucun rapport historique, ne certifie pas globalement L1-F2 et ne vaut ni PASS I1 ni qualification G1/G2/G3. Aucun nouvel audit L1-F2 n’est demandé. Les verdicts de préparation ci-dessous sont des instantanés antérieurs à cette décision.

Exécution I1 réalisée (détail et PASS local borné au §9) depuis le HEAD distant vérifié `2b4d46cf08509c95fc490afa1d66ae38f41957b6`, worktree isolé `/private/tmp/pocoma-v3-l2-i1-execution`. Périmètre déclaré avant code : COMMAND primaire ; READ et monolithe en preuves complémentaires ; aucun comportement EVENT/PROJECTION/WEB/BINDING/LKV modifié. Gate architecture requis (isolation/composition), full reactor non requis. Commandes de clôture : les quatre commandes du §8. DB CURRENT V3 neuf ; historiques L1 inchangés. I2–I5 exclus.

## 1. Quatre réserves de la revue et résolution

| Réserve | Résolution dans ce plan | Preuve exigible |
|---|---|---|
| Bascule du slot avant Engine Command | I1 installe un schéma **inactif** sur deux cibles neuves ; aucun writer V3 pilote ne démarre. L'ancien slot n'existe pas dans le schéma neuf. I2 seul raccorde Engine, participant et suppression R1. | Garde exécutable de composition, privilèges sans DML, migrations et tests de démarrage négatifs y compris monolithe ; régressions L1 sur leurs schémas historiques. |
| Routage/bootstrap sous concurrence | Quatre tables, contraintes simples, routage vérifié en SQL, transaction d'amorçage sérialisée par verrou de table. L'advisory lock initial est retiré : il ne protégeait que les participants volontaires et n'ajoutait rien au verrou de table + privilèges. | Deux connexions admin, erreurs injectées, catalogue/état après rollback, refus cible erronée, droits runtime et tests de partitions/hash. |
| Types Java I2 prématurés | I1 ne crée ni `ConsumptionParticipant`, ni `SlotCompletion`, ni `RecoveryObservation`, ni port/adapter de lecture de contrat sans consommateur actif. R1/R9 demeurent dans la norme pour I2. | Inventaire des classes créées et usages réels ; aucun type mort ou couche relais. |
| Identités/configuration divergentes | Neuf UUID et `P=4` ratifiés pour le pilote, deux autorités séparées. Un test compare le catalogue Java, les lignes SQL de bootstrap, la définition SQL du trigger Command et les 21 vecteurs indépendants. | Test de concordance qui échoue si seul l'UUID ExecuteCommand Java, le seed ou le trigger change. |

## 2. G0 : prérequis, preuves et décision

| Condition normative | Preuve documentaire | Statut réel et action restante | Impact I1 |
|---|---|---|---|
| P0, décisions A01–A12/R1–R10, frontières et manifeste | Plan L2, registre, consolidation, [clôture P0](L2_Post_Audit_Corrections_Closure.md), [contre-audit L2](L2_Independent_Counter_Audit_and_G0_Assessment.md), manifeste et oracles | **Satisfait pour la conception.** Les méthodes du manifeste restent prévisionnelles ; leurs PASS relèvent de G1/G2. | Pas de blocage conceptuel. |
| GO humain d'implémentation | Mandat explicite du responsable du 26-10-2026, présent dossier | **Donné.** Il ne vaut ni PASS I1 ni ACCEPTED L2. | Autorisation humaine disponible sous les autres préconditions G0. |
| Configuration structurelle pilote | Tableau §4 et convention SHA-256 du plan L2 §6 | **Ratifiée par le présent mandat :** neuf UUID uniques, sept familles, deux WRITE/sept READ, `P=4` commun par famille. À matérialiser et vérifier en I1 ; aucune décision de dimensionnement production. | Pas de blocage. |
| Validation/audit indépendant L1-F2 | [Rapport interne F2](L1_F2_Closure_Report.md), [preuves](../testing/evidence/L1-F2/Gate-index.json) ; audit historique publié à `055e34eb`, fichier `docs/v3/L2_Contradictory_Independent_Architectural_Audit_Report.md`, § « Validation indépendante du prérequis L1-F2 », consultable par `git show 055e34eb:docs/v3/L2_Contradictory_Independent_Architectural_Audit_Report.md` ; [contre-audit courant](L2_Independent_Counter_Audit_and_G0_Assessment.md#8-l1-f2-axes-daudit-et-actions) | **Audit retrouvé, validation formelle non établie.** Le rapport historique concluait PASS après lecture de code, DDL et mesures, sans réexécution. Il a été supprimé à `bc1f64b8` pour erreurs de référentiel. Le contre-audit confirme des archives cohérentes et les méthodes obligatoires PASS mais juge ce « PASS indépendant » trop fort : skip exploratoire V2, portée WRITE bornée, aucune garantie générale `O(1)`. Manque une conclusion indépendante **bornée et encore recevable** acceptant explicitement ces limites. Retrouver une telle pièce existante si elle existe ; ne pas rétablir le rapport supprimé ni lancer un audit général par défaut. | **Réserve historique levée humainement le 26-10-2026.** Acceptation des preuves avec leurs limites pour poursuivre I1 ; aucun certificat global. |
| DDL, tests PostgreSQL et moteur L2 | Plan et oracles prospectifs | À produire en I1–I5, juger en G1/G2. | Ne bloque pas G0. |

**Décision courante : G0 franchi par décision humaine explicite.** La réserve procédurale de recevabilité L1-F2 est levée par le responsable, qui accepte les preuves existantes et leurs limites documentées. Le rapport historique et le contre-audit restent inchangés. Cette décision autorise I1 et ne vaut ni PASS technique I1 ni certification G1/G2/G3. Aucun nouvel audit L1-F2 ne conditionne cette exécution.

## 3. Bascule du slot Command et invariants L1

I1 est une fondation de schéma et de valeurs **sans exécution** : pas de Discovery, acquisition, renewal, claim, cession, finalisation, boucle, admission par le pilote ou appel à `ExecutePotCommand.execute`. Il n'existe pas de seconde voie transactionnelle temporaire. Le schéma neuf ne contient que `consumption_slot` ; l'ancien `v3_command_slot` est retiré dans la migration neuve après contrôle de vacuité. Les cibles L1/V2 et les locations Flyway de leurs tests ne sont ni modifiées ni vidées. Un schéma existant avec commandes/slots appelle un mandat de migration distinct ; le bootstrap I1 le refuse.

**Garde d'activation exécutable :** une configuration pilote nommée explicitement et fermée par défaut assemble uniquement le catalogue et un contrôleur de compatibilité ; elle ne déclare aucun `ApplicationRunner`, `@Scheduled`, listener, poller, `SmartLifecycle` actif ni bean exécuteur. Avant le démarrage de tout composant de traitement, la garde valide l'identité des deux bases/schémas/rôles, quatre tables et absence de `v3_command_slot`, neuf routes attendues, partition ownership complète et version/hash. Elle inspecte les beans effectifs et refuse les anciens `JdbcPotCommandStore`/`PotCommandJdbcTransactions` owners, `ExecutePotCommand` exposé comme worker, ou `JpaConsumption*`/pollers autonomes raccordés aux mêmes autorités. `V3PotCommandComposition` est une fabrique statique : l'inspection des beans seule ne peut pas la détecter. Les imports explicites de `PocomaRuntimeMonolithApplication` et `PocomaCommandConsumptionWorkerApplication` doivent donc rendre le profil pilote et leurs configurations de traitement héritées mutuellement exclusifs ; un test tente leur import simultané et exige un refus avant démarrage. Le monolithe subit **la même** garde après assemblage et avant lancement des cycles. Tester démarrage valide inerte et chaque démarrage incompatible, dont import indirect monolithe. Ne pas compter sur un seul drapeau de configuration ni sur l'absence d'appels spontanés. Rôle runtime I1 sans DML sur les nouvelles tables ; l'ancien writer, même invoqué par erreur contre le schéma neuf, échoue sur la table dédiée absente et rollback, sans publication partielle. I2 remplacera cette inactivité par un unique owner physique et ses tests.

**Contrat différé L1 vs I1.** Le SQL L1 de `V2__command_execution.sql` déclare `v3_command_unit` (AFTER INSERT/UPDATE `v3_command`) et `v3_result_unit` (AFTER INSERT `v3_command_result`), tous deux `DEFERRABLE INITIALLY DEFERRED` vers `v3_require_command_unit()`. Pour PENDING, résultat interdit. Pour TERMINAL : résultat obligatoire, ancien slot absent, complétion positionnée obligatoire ; CHANGED exige POT event positionné, bon Pot/version et composantes ; UNCHANGED/REJECTED interdisent l'événement Pot. I1 ne change **que** le prédicat d'absence du slot : `consumption_slot` pour l'UUID ExecuteCommand et le `request_id`. Conserver textuellement les autres branches et la fonction/triggers `v3_pot_event_request` (AFTER INSERT événement, lien exact avec commande/resultat), les PK/FK/CHECK/UNIQUE, les triggers d'immuabilité résultat/publication/positions, `v3_advance_pot_cas`, `v3_require_effective_version` et les contraintes du schéma Pot V1/V3/V4. La migration I1 doit comparer `pg_get_functiondef` et `pg_trigger` avant/après, pas seulement lister trois noms. Aucun invariant du trigger L1 n'est supprimé. La FK de l'ancien slot vers `v3_command` disparaît avec sa table ; la nouvelle clé composite et sa FK au contrat la remplacent, tandis que l'existence de la source Command et la transition terminale relèvent de la transaction/trigger Command et du futur writer I2.

**Vérifiable en I1 :** DDL, catalogue des triggers/contraintes, PENDING sans résultat et sans slot, TERMINAL incomplet refusé, terminal avec slot canonique refusé, CHANGED sans événement/composante refusé, no-effect avec événement refusé, unité SQL fixture complète acceptée, rollback atomique. Ajouter un test négatif d'un slot sur un autre UUID afin de montrer la portée exacte du prédicat. **À prouver en I2 :** suppression Engine unique et exacte avant suffixe R1, même Connection/XID/manager, poisoning même erreur interceptée, ordre slot→source→Identity→Pot, token/horloge après verrou, résultat/effets/positions et suppression dans un commit, ACK incertain et recovery R9. Les fixtures SQL I1 ne valent pas exécution du moteur.

## 4. Configuration pilote et catégories de paramètres

Les UUID suivants sont des littéraux durables, pas des UUID recalculés au démarrage. L'unicité (9/9), l'allocation aux sept familles normatives et la séparation WRITE (2)/READ (7) ont été contrôlées. La définition READ de banc `c08982eb-e046-52d2-93f6-19c5eb5ed896` est hors catalogue métier et n'est installée que dans ses tests.

| Consumption | UUID | Famille / effets |
|---|---|---|
| ExecuteCommand | `98f7268d-d340-581c-b83d-010926223e14` | COMMAND / WRITE |
| ExecuteIdentityRequest | `2999e75c-272d-5afe-a966-c9364ef021fe` | IDENTITY_REQUEST / WRITE, inactive |
| MaterializePotVersion | `3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a` | POT_EVENT / READ |
| MaterializePotBalance | `190a93c6-b8af-5ac7-8001-72e2ec53c2df` | POT_EVENT / READ |
| MaterializePotVisibility | `b4d04cce-b5da-5d1b-9698-5ff9af9a46fa` | POT_EVENT / READ |
| MaterializeInvitation | `924cd9e3-435d-5804-9d1e-c6aecadb9ab4` | INVITATION_EVENT / READ |
| MaterializeCurrentBinding | `7b8d2838-bf7a-5117-868a-e5c14b4c0221` | IDENTITY_EVENT / READ |
| MaterializeCommandResult | `63664303-d33e-5d42-8cce-c4dae8023311` | COMMAND_COMPLETION_EVENT / READ |
| MaterializeIdentityRequestResult | `1d8ff246-358f-5ddd-a7f5-416efcfd7445` | IDENTITY_COMPLETION_EVENT / READ |

**Structure figée avant I1 :** sept chaînes de famille, neuf UUID, autorité de chaque définition, `P=4` pour chaque famille, SHA-256 v1 et octets canoniques : ASCII `pocoma-consumable-v1`, octet zéro, famille ASCII, octet zéro, 16 octets UUID réseau ; quatre premiers octets du digest en int32 signé big-endian ; `floorMod(hash,P)`. Les 21 vecteurs du plan L2 §6 sont les oracles indépendants, y compris digest et `p4/p16`. `P=4` est une contrainte de **ce pilote** ; le changement après slots/K requiert une procédure exceptionnelle explicite, jamais un redéploiement.

**Nécessaire aux preuves I1 :** identité des cibles/rôles, concordance Java/seed/trigger/SQL, 21 vecteurs, refus de configuration divergente et de writers incompatibles. **Hypothèses expérimentales conservées pour I3–I5/P4/P7 :** `softCap=64`, fenêtre `W=128`, lot `B=32`, `M_C=2`, `M_W=4`, `N=3`, `retryDelay=30 s`, lease partition `30 s`, claim `60 s`, renouvellement cible `10 s`, `lock_timeout=500 ms` pour renouvellement ; deux connexions claims et une leases réservées par autorité. Les bornes de polling et le tour circulaire se fixent avant I4/I5. Archiver/valider ces valeurs à leur étape productrice, les ajuster sur durées DB, contention et renouvellements réellement mesurés. Elles ne sont ni SLO, ni garanties de performance, ni paramètres de production finale. Aucune mesure d'un moteur non écrit ne bloque I1.

## 5. DDL I1 révisé, commun aux deux autorités

Flyway applique le bloc dans un schéma V3 neuf explicitement identifié, via un rôle admin sans droits pour le Worker. Les quatre tables sont les seuls objets **durables de coordination** ajoutés. `pgcrypto` fournit SHA-256 : préinstaller administrativement l'extension dans `public`, vérifier `to_regprocedure('public.digest(bytea,text)')` dans la migration et refuser si elle manque ; ne pas laisser le `search_path` décider quelle fonction `digest` sera appelée. SQL ci-dessous est le contrat d'implémentation ; les migrations WRITE/READ sont distinctes mais leur bloc commun doit être identique. `partition_count=4` et `routing_version='sha256-v1'` rendent la cohérence par famille déclarative dans ce pilote, sans trigger d'insertion de contrat ni advisory lock. Le contrat demeure non modifiable.

```sql
do $$
begin
  if to_regprocedure('public.digest(bytea,text)') is null then
    raise exception 'pgcrypto digest missing in public' using errcode='42883';
  end if;
end $$;
create table consumption_routing_contract (
  consumption_id uuid primary key,
  family text not null check (family in ('COMMAND','IDENTITY_REQUEST','POT_EVENT',
    'INVITATION_EVENT','IDENTITY_EVENT','COMMAND_COMPLETION_EVENT',
    'IDENTITY_COMPLETION_EVENT')),
  routing_version text not null check (routing_version = 'sha256-v1'),
  partition_count integer not null check (partition_count = 4)
);
create function consumption_contract_immutable() returns trigger language plpgsql as $$
begin
  raise exception 'immutable routing contract' using errcode='23514';
end $$;
create trigger consumption_contract_immutable before update or delete
  on consumption_routing_contract for each row execute function consumption_contract_immutable();

create function consumption_hash_v1(f text, id uuid) returns integer
language sql immutable strict as $$
  with bytes as (
    select public.digest(convert_to('pocoma-consumable-v1','UTF8') || decode('00','hex')
      || convert_to(f,'UTF8') || decode('00','hex') || uuid_send(id),'sha256') as d
  ), unsigned as (
    select get_byte(d,0)::bigint*16777216 + get_byte(d,1)::bigint*65536
      + get_byte(d,2)::bigint*256 + get_byte(d,3)::bigint as u from bytes
  )
  select (case when u >= 2147483648 then u-4294967296 else u end)::integer
  from unsigned
$$;

create table partition_ownership (
  consumption_id uuid not null references consumption_routing_contract(consumption_id),
  partition_id integer not null check (partition_id between 0 and 3),
  owner_token uuid, owned_until timestamptz,
  primary key (consumption_id,partition_id),
  check ((owner_token is null) = (owned_until is null))
);
create table discovery_checkpoint (
  consumption_id uuid not null references consumption_routing_contract(consumption_id),
  partition_id integer not null check (partition_id between 0 and 3),
  position bigint not null check (position >= 0),
  primary key (consumption_id,partition_id)
);
create table consumption_slot (
  consumption_id uuid not null references consumption_routing_contract(consumption_id),
  consumable_id uuid not null,
  partition_hash integer not null,
  partition_id integer not null check (partition_id between 0 and 3),
  claim_token uuid, claimed_until timestamptz, retry_after timestamptz,
  attempt_count integer not null default 0 check (attempt_count >= 0),
  state text not null default 'AVAILABLE'
    check (state in ('AVAILABLE','QUARANTINED')),
  primary key (consumption_id,consumable_id),
  check ((claim_token is null) = (claimed_until is null)),
  check (state <> 'QUARANTINED' or
    (claim_token is null and claimed_until is null and retry_after is null))
);
create index consumption_slot_partition_walk_idx
  on consumption_slot(consumption_id,partition_id,consumable_id);
create index consumption_slot_live_count_idx
  on consumption_slot(consumption_id,partition_id) where state <> 'QUARANTINED';
create index consumption_slot_available_due_idx
  on consumption_slot(consumption_id,partition_id,retry_after,claimed_until)
  where state = 'AVAILABLE';

create function consumption_row_guard() returns trigger language plpgsql as $$
declare f text;
begin
  select family into f from consumption_routing_contract
    where consumption_id = new.consumption_id;
  if not found then raise exception 'missing route' using errcode='23503'; end if;
  if tg_table_name = 'consumption_slot' then
    if new.partition_hash <> consumption_hash_v1(f,new.consumable_id)
       or new.partition_id <> ((new.partition_hash::bigint % 4 + 4) % 4) then
      raise exception 'slot route mismatch' using errcode='23514';
    end if;
    if tg_op = 'UPDATE' and
       (new.consumption_id,new.consumable_id,new.partition_hash,new.partition_id)
       is distinct from
       (old.consumption_id,old.consumable_id,old.partition_hash,old.partition_id) then
      raise exception 'slot key immutable' using errcode='23514';
    end if;
  elsif tg_table_name = 'discovery_checkpoint' then
    if f in ('COMMAND','IDENTITY_REQUEST') then
      raise exception 'burnable checkpoint forbidden' using errcode='23514';
    end if;
    if tg_op = 'UPDATE' and
       ((new.consumption_id,new.partition_id) is distinct from
        (old.consumption_id,old.partition_id) or new.position < old.position) then
      raise exception 'checkpoint regression' using errcode='23514';
    end if;
  elsif tg_op = 'UPDATE' and
    (new.consumption_id,new.partition_id) is distinct from
    (old.consumption_id,old.partition_id) then
    raise exception 'ownership key immutable' using errcode='23514';
  end if;
  return new;
end $$;
create trigger consumption_slot_guard before insert or update on consumption_slot
  for each row execute function consumption_row_guard();
create trigger discovery_checkpoint_guard before insert or update on discovery_checkpoint
  for each row execute function consumption_row_guard();
create trigger partition_ownership_guard before insert or update on partition_ownership
  for each row execute function consumption_row_guard();
```

Le cast `bigint` protège `Integer.MIN_VALUE`. Les FK et l'immuabilité du contrat stabilisent la lecture du trigger. Les PK interdisent doublon de slot, ownership et K ; aucun `ON DELETE CASCADE`, `TRUNCATE`, job de purge, état DONE/UNKNOWN, compteur softCap ou cinquième table. En I1 le runtime ne possède aucun DML sur ces tables : les triggers préparent les writers I2–I4 sans prétendre les certifier. Les index sont des candidats à mesurer, pas des garanties de plan PostgreSQL.

**Tail WRITE uniquement :** vérifier la vacuité de `v3_command_slot` **dans la transaction Flyway** et refuser si non vide ; `CREATE OR REPLACE FUNCTION v3_require_command_unit()` reprend exactement le corps L1, en substituant uniquement
`exists(select 1 from v3_command_slot where request_id=c.request_id)`
par
`exists(select 1 from consumption_slot s where s.consumption_id='98f7268d-d340-581c-b83d-010926223e14'::uuid and s.consumable_id=c.request_id)`.
Faire ensuite `DROP TABLE v3_command_slot`, sans `CASCADE`, dans cette même migration. Si le DROP révèle un dépendant non inventorié, la migration échoue et l'inventaire est corrigé ; pas de suppression implicite. Les triggers `v3_command_unit`, `v3_result_unit`, `v3_pot_event_request` sont conservés et catalogués. Ajouter `create index v3_command_pending_request_idx on v3_command(request_id) where status='PENDING'`; mesurer avec TERMINAL nombreux à I4. La vérification de cible Flyway précède les DDL dans sa transaction ; les schémas L1 de test ne reçoivent pas V5.

## 6. Bootstrap administratif et concurrence

Une **transaction par autorité**, sans prétendre une transaction entre les deux bases. Flyway et le seed peuvent être deux transactions successives ; la composition pilote reste inerte et sa garde exige **les deux catalogues complets** avant tout démarrage. La vérification externe de la cible sert à choisir la connexion ; elle est **répétée dans la transaction**, après verrou, pour fermer la race entre contrôle préalable et `BEGIN`. Pas de réparation, `ON CONFLICT DO NOTHING`, seed au redémarrage ou reconstruction implicite.

Procédure exacte du seed, par connexion admin épinglée à une base et un schéma attendus (WRITE : deux lignes ; READ : sept). Exécuter avec `psql -v ON_ERROR_STOP=1 -v authority=WRITE|READ`, sans rôle runtime :

```sql
begin;
lock table consumption_routing_contract in share row exclusive mode;
-- Les assertions de cible sont faites ici, sous le verrou, avant tout INSERT.
select set_config('pocoma.bootstrap_authority', :'authority', true);
do $$
declare a text := current_setting('pocoma.bootstrap_authority');
begin
  if a not in ('WRITE','READ')
     or current_database() <> case a when 'WRITE' then 'EXPECTED_WRITE_DB'
                                     else 'EXPECTED_READ_DB' end
     or current_schema() <> case a when 'WRITE' then 'EXPECTED_WRITE_SCHEMA'
                                    else 'EXPECTED_READ_SCHEMA' end
     or current_user <> 'EXPECTED_ADMIN_ROLE'
     or exists(select 1 from consumption_routing_contract)
     or exists(select 1 from partition_ownership)
     or exists(select 1 from discovery_checkpoint)
     or exists(select 1 from consumption_slot) then
    raise exception 'wrong or nonempty L2 bootstrap target' using errcode='23514';
  end if;
end $$;
with definitions(id,family,authority) as (values
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
select id,family,'sha256-v1',4 from definitions
where authority=current_setting('pocoma.bootstrap_authority');
insert into partition_ownership(consumption_id,partition_id)
select c.consumption_id,p from consumption_routing_contract c
cross join generate_series(0,3) p;
do $$
declare a text := current_setting('pocoma.bootstrap_authority');
begin
  if (select count(*) from consumption_routing_contract) <>
     case a when 'WRITE' then 2 else 7 end
     or (select count(*) from partition_ownership) <>
     case a when 'WRITE' then 8 else 28 end
     or exists(select 1 from discovery_checkpoint)
     or exists(select 1 from consumption_slot) then
    raise exception 'incomplete routing bootstrap' using errcode='23514';
  end if;
end $$;
commit;
```

Les noms `EXPECTED_*` sont des paramètres **obligatoires** à remplacer par l'inventaire signé de la cible, pas des valeurs de déploiement. Vérifier aussi le serveur/port/identité de cluster hors SQL et conserver leur preuve. Le `LOCK TABLE ... SHARE ROW EXCLUSIVE` est auto-exclusif : un second admin attend, voit ensuite le catalogue non vide et rollback. Aucun advisory lock n'est nécessaire ; il ne protégerait pas un admin qui l'ignore. La table contractuelle, son CHECK pilote et ses privilèges rendent les inserts compatibles ; le trigger interdit UPDATE/DELETE. Un administrateur propriétaire reste de confiance pour DDL exceptionnel, jamais runtime. Pour une reconfiguration future, arrêter les writers et soumettre un plan explicite ; pas de mutation ad hoc sous slots/K.

**Grants I1 :** admin propriétaire/DDL/seed ; runtime `USAGE` schéma et `SELECT` sur contrat/ownership/checkpoint/slot, aucun `INSERT/UPDATE/DELETE/TRUNCATE` sur les quatre, aucun `CREATE` schéma ni `ALTER/DROP`. Révoquer aussi les privilèges hérités via `PUBLIC`/rôles membres, vérifier `has_table_privilege` avec les véritables identités SQL. Accorder aux writers futurs les DML minimaux seulement à l'étape qui prouve leur protocole. Les FK n'autorisent aucune purge ; les droits ne sont pas une preuve de R8 si un owner admin déploie un script destructeur : inventorier scripts/jobs séparément.

| Course | Ressources et ordre ; concurrents | États committés / rollback / test PostgreSQL |
|---|---|---|
| Deux admins seed | Même table contrat verrouillée avant tout contrôle interne, puis inserts contrat→ownership ; deux sessions admin | Un catalogue exact ou aucun ; seconde session échoue proprement après attente. Barrière et `pg_blocking_pids`, pas sleep. |
| Cible préalable vs ouverture de transaction | Contrôle externe puis `BEGIN`→verrou contrat→recontrôle DB/schema/rôle/vacuité ; admin erroné | Mauvaise cible ou seed partiel : rollback sans route/ownership. Test substitution de cible et faute après première insertion. |
| Contrat vs futur lease I3 | Contrat immuable + SELECT dans transaction lease, puis verrou ownership et horloge DB post-attente ; admin n'a aucune mise à jour ordinaire | Route exacte ou refus d'acquisition ; aucun état intermédiaire observable. Test route manquante/mismatch et contrôle transactionnel I3. |
| Deux Discoveries I4 même (C,p) | Ownership→heure/token→compte réel→K éventuel→slots→K ; réactivation admin ne prend que slot | Préfixe K et slots tous anciens ou tous nouveaux ; rollback complet ; dépassement softCap par réactivation permis R2. P2 avec barrières. |
| Burnable Discovery I4 vs finaliseur I2 | Discovery ownership→source PENDING verrouillée→lecture non bloquante du slot exact→INSERT si absent ; finaliseur slot→source→Identity→Pot→effets→suppression R1→suffixe, jamais ownership | PENDING+slot ou TERMINAL+effets sans slot. Conflit de PK revalidé, jamais `ON CONFLICT` aveugle. P3/P5 : ordres forcés, `pg_blocking_pids`, rollback et absence de deadlock. |
| Deux finaliseurs, renewal/takeover I2/I3 | Slot seul verrouillé, heure DB après attente ; claim/token revalidés ; verrou jusqu'à résolution physique | Un seul effet ; ancien token inopérant ; expiration après verrou admissible. P4/P5, deux connexions et snapshot extérieur. |
| Cession/réactivation I3 | Après rollback confirmé : slot→heure/token CAS ; admin réactive le seul slot QUARANTINED, sans ownership | Au plus un incrément ou une réactivation ; incertitude ne produit aucune cession supposée. P6. |

Ces cinq derniers protocoles restent des obligations I2–I4, non des fonctionnalités I1. La simplicité du DDL ne remplace ni les verrous réels ni les tests de concurrence aux étapes productrices.

## 7. Ordre exact d'implémentation I1, après franchissement G0

| Ordre | Fichier(s) | Sortie exigée avant suivant |
|---|---|---|
| 0 | [plan directeur](V3_Implementation_Master_Plan.md), présent plan | Consigner la dérogation humaine explicite et dater G0 ; vérifier HEAD distant, worktrees et cible. |
| 1 | `app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/v3/{ConsumableFamily,ConsumptionId,ConsumableId,ConsumptionDefinition,RoutingV1}.java` et `.../src/test/java/.../v3/RoutingV1Test.java` | Valeurs pures réellement utilisées par le catalogue/guard ; 21 vecteurs indépendants, `floorMod` signé. Éviter wrappers inutilisés : si `ConsumableId` n'a pas de consommateur I1, différer ce fichier. |
| 2 | `app/infra-persistence-primary-jpa/src/main/resources/db/v3-consumption-write/migration/V5__consumption_foundation.sql` ; `app/infra-persistence-read-jdbc/src/main/resources/db/v3-consumption-read/migration/V1__consumption_foundation.sql` | Schémas communs, triggers/index/grants, tail WRITE contrôlée ; aucune migration ancienne éditée. |
| 3 | `scripts/v3/bootstrap-l2-routing.sql` et son inventaire cible sous `docs/v3/` si nécessaire | Seed admin, recontrôle intra-transaction, deux bases, rollback et double admin. Aucun bootstrap au lancement Worker. |
| 4 | `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/{V3ConsumptionCatalogConfiguration,V3BootstrapGuard}.java` ; `app/runtime-monolith/src/main/java/com/kartaguez/pocoma/PocomaRuntimeMonolithApplication.java` ; `app/runtime-command-consumption-worker/src/main/java/com/kartaguez/pocoma/PocomaCommandConsumptionWorkerApplication.java` | Catalogue littéral, imports mutuellement exclusifs et garde avant traitement, zéro worker pilote actif, séparation datasource/manager. Ne pas créer de nouveau moteur/port. |
| 5 | `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/consumption/ConsumptionFoundationPostgresTest.java` ; `app/infra-persistence-read-jdbc/src/test/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/v3/consumption/ReadConsumptionFoundationPostgresTest.java` ; `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/ConsumptionBootstrapPostgresTest.java` ; `app/runtime-monolith/src/test/java/com/kartaguez/pocoma/ConsumptionPilotIsolationTest.java` | DDL, seed, concurrence, trigger négatif, rôle runtime, concordance Java/SQL/seed, 21 vecteurs, démarrages valides/incompatibles. |
| 6 | Inventaire de passage I2 dans ce plan/manifestes existants | Vérifier les anciens writers et POM/imports, archiver preuves I1 et donner à I2 le handoff unique. Pas de fichier de rapport redondant. |

`engine-consumption`, `port-transaction`, `infra-tx-spring`, `engine-write-pot` et `JdbcPotCommandStore` ne reçoivent **aucun nouveau contrat de production I1**. Si le test de composition révèle qu'une interface/adapter de lecture est réellement nécessaire, justifier son consommateur et revoir la tranche avant de l'ajouter. Les contrats `ConsumptionParticipant`, `SlotCompletion`, `RecoveryObservation` restent normatifs pour I2. L'injection demeure explicite ; pas de scan implicite, port relais, deuxième owner ou transaction imbriquée.

## 8. Oracles, slices, risques et sortie

| Preuve I1 | Oracle indépendant et négatifs |
|---|---|
| Identités/routage | Comparer liste Java à neuf lignes SQL et aux littéraux du script ; `pg_get_functiondef(v3_require_command_unit)` doit contenir l'UUID ExecuteCommand Java ; changer **un seul** des trois et exiger l'échec. Recalcul externe Python des 21 vecteurs (digest/hash/p4/p16), puis Java et fonction SQL contre ces valeurs ; familles et autorités exactes. |
| DDL/immutabilité | `pg_catalog` sur deux autorités : quatre tables, PK/FK/CHECK/triggers/index, P=4/version ; INSERT hash ou partition faux, checkpoint burnable, régression K, modification de clé, UPDATE/DELETE contrat et rôle runtime interdits. Aucun DELETE/TRUNCATE implicite. |
| Command L1→I1 | Comparaison structurée du corps du trigger L1/I1, inventaire de tous ses triggers et dépendants ; tests PG négatifs PENDING avec résultat, TERMINAL sans résultat/complétion, avec slot, CHANGED sans POT/composante, no-effect avec POT ; fixture complète qui commit ; états inchangés après rollback. Garder les tests L1 sur anciennes locations. |
| Bootstrap/composition | Deux admins réellement concurrents, faute à chaque frontière seed, mauvaise cible/profil, re-run, rôles hérités, restart sans DDL, ancien writer/monolithe activé : refus. Snapshot externe des deux bases et de la cible historique ; zéro traitement I1. |

**Déclaration prospective de vérification, avant code I1 :** impact attendu : valeurs domaine de routage, DDL WRITE/READ, seed et composition inactive, y compris imports monolithe/worker. Primaire **COMMAND** ; preuve directe complémentaire des modules READ et monolithe. Secondaires canoniques **NONE** si aucun comportement EVENT/PROJECTION ne change. Impact interdit : WEB/BINDING/LKV, moteurs I2–I5, Identity réelle, projections métier et API. Toute nécessité de changer leur production est un franchissement inattendu à déclarer **avant** la modification. Depuis `app/` : `./mvnw -pl runtime-command-consumption-worker -am test`, `./mvnw -pl infra-persistence-read-jdbc -am test`, `./mvnw -pl runtime-monolith -am test`. Les deux dernières sont des preuves directes complémentaires, pas des slices canoniques inventées. **Gate architecture global requis à I1** car les imports/règles d'isolation du monolithe changent : `./mvnw -pl architecture-tests -am test`. DB **CURRENT V3 neuf**, pas de replay historique V2 par défaut. Full reactor `./mvnw test` **non requis à I1** si l'impact reste borné ; requis à la clôture majeure L2 / intégration, ou plus tôt seulement si impact réellement non bornable. Aucun `-amd` automatique.

**Critères de sortie I1 :** deux cibles neuves isolées, quatre tables/contrats complets, aucune table dédiée Command sur le pilote, aucun writer actif, anciens tests L1 préservés, 21 vecteurs et concordance des UUID, bootstrap/locks/rollback/privilèges prouvés, triggers terminaux sans perte d'invariant, preuves PG et Maven archivées avec SHA et skips. I2 peut alors recevoir le protocole R1/R9 ; G1/G2 restent futurs, aucun PASS L2 global. Risques à suivre après I1 : coût de l'index PENDING et renouvellements à qualifier après I3–I5 ; les limites historiques F2 sont acceptées pour G0 et ne sont pas une certification globale. Les preuves SQL/extension et monolithe sont rattachées au §9. Aucune de ces mesures futures n'est faussement exigée pour I1.

**Contrôle de ce seul amendement :** slice exécutée **NONE** (documentation). Contrôles statiques, liens, vecteurs et `git diff --check` ; aucun Maven/PostgreSQL/migration exécuté. Franchissement inattendu **aucun**, gate global **non requis**, full reactor **non exécuté car documentaire**. La publication cible `feat/v3-architecture` sans force ; consigner SHA final et concordance distante après push.


## 9. Réalisation I1 et preuves bornées

### Code et architecture livrés

Départ distant recontrôlé : `2b4d46cf08509c95fc490afa1d66ae38f41957b6`. Décision G0 : commit `46cb5543`. Fondations : `caeee1a1`. Correction des filtres de scan Spring Boot : `12eaa5ae983c23a4b07c0299ce59c4d7933bfb5d`. Le code applicatif reste figé à `12eaa5ae` pendant les gates. `6835b5c15cc40d09c22e0b0a4f3b6538ac49992d` ajoute uniquement le package V3 prévu à l'inventaire fermé du test d'architecture : COMMAND/READ/monolithe sont rattachés à `12eaa5ae`, architecture à `6835b5c1`. Les empreintes et le diff prouvent que production, migrations, scripts et tests des trois autres gates sont identiques. Les modifications documentaires ultérieures ne changent pas cette arborescence.

- `domain-consumption/.../v3` : sept familles, `ConsumptionId`, définition immutable et autorité, `RoutingV1` SHA-256 réseau/signé/floorMod. `ConsumableId` différé comme prévu : aucun consommateur I1 ; aucun participant, completion, recovery ou port inutilisé.
- WRITE V5 et READ V1 dans leurs **nouvelles locations explicites** : quatre tables de coordination, contraintes, index et triggers communs identiques ; contrôle DB/schéma/admin/vacuité ; extension `public.pgcrypto` requise ; droits runtime SELECT uniquement, y compris rôles accessibles par SET ROLE. WRITE retire seulement la table dédiée vide et substitue le prédicat exact Command ; fonctions/triggers/contraintes L1 catalogués et comparés.
- `scripts/v3/bootstrap-l2-routing.sql` : transaction par autorité, verrou de table avant assertions internes, neuf UUID littéraux filtrés par autorité, ownership complet. Pas de réparation/reseed au démarrage. Deux admins concurrents prouvés par `pg_blocking_pids` ; fautes avant/après INSERT et avant COMMIT avec rollback complet.
- `composition-runtime-spring/.../consumption/v3` : catalogue explicite et garde injectable, deux connexions en lecture, contrôle des identités physiques, contrats, droits, partitions et trigger canonique. Dépendance directe sur `domain-consumption`, déjà transitif : aucune restructuration large du graphe.
- Racines worker Command et monolithe : scan/imports hérités dans une configuration exclusive du profil `v3-consumption-pilot` ; filtres Spring Boot préservés. Pilote sans Flyway/DDL/JPA automatique, worker, runner ni boucle. Les anciens types owners/writers/pollers sont refusés avant construction ; garde finale avant refresh/cycles. La fabrique statique historique n'est pas rendue active.
- Tests : `RoutingV1Test`, `ConsumptionFoundationPostgresTest`, `ReadConsumptionFoundationPostgresTest`, `ConsumptionBootstrapPostgresTest`, `ConsumptionPilotIsolationTest`, `V3ConsumptionFoundationArchitectureTest`. La règle existante EVENT/PROJECTION admet explicitement la configuration héritée imbriquée du **seul** monolithe, comme sa racine préalable ; aucune permission générale supplémentaire.

### Vérification et écarts d'exécution

Slice déclarée **COMMAND**, compléments READ/monolithe ; secondaires canoniques **NONE**. Impact observé conforme : valeurs, DDL, administration et composition inactive. Aucun changement de production aux moteurs/transactions/ports Command historiques, ni WEB/BINDING/LKV/EVENT/PROJECTION métier. **Franchissement inattendu : aucun. Gate architecture global requis et exécuté. Full reactor non requis/non exécuté** : périmètre I1 borné, pas de clôture majeure L2 ni intégration main. Les migrations historiques exécutées transitivement par les tests existants des gates sont conservées dans les logs ; aucune nouvelle baseline TRUSTED n'est revendiquée.

Les tests PostgreSQL utilisent `postgres:17-alpine`, version effective **17.11**, Testcontainers, Java 21. Les URLs/ports, identités de conteneurs/clusters, image, configuration, catalogues et états durables se trouvent dans [les preuves I1](../testing/evidence/L2-I1/Gate-index.json). Un démarrage historique a initialement échoué parce que le scan imbriqué avait perdu les filtres Boot excluant les fixtures de test ; correction `12eaa5ae`, contrôle ciblé puis relance intégrale. Les premiers essais ont aussi corrigé la parenthésation du CASE du seed et des fixtures négatives. Le premier gate d'architecture a aussi signalé l'inventaire fermé des packages domaine, qui omettait le nouveau package `v3` déjà prévu par le plan ; correction du test seul dans `6835b5c1`, 56 contrôles structurels ciblés PASS, puis relance du gate canonique complet. Les règles de pureté ne sont pas affaiblies. Les tentatives intermédiaires restent des diagnostics, jamais des PASS.

Adaptations d'implémentation dans le contrat approuvé : `CASE` parenthésé pour PostgreSQL ; verrou exclusif des tables préexistantes **avant** leur contrôle de vacuité dans la transaction de migration ; refus d'autorité SQL incompatible avec la présence de la source Command ; vérification des privilèges des rôles accessibles même NOINHERIT. Aucun choix architectural A/R rouvert, aucun écart fonctionnel au plan.

<!-- I1_GATE_RESULTS -->
| Gate / commande exacte depuis `app/` | Tests | Échecs | Erreurs | Skips |
|---|---:|---:|---:|---:|
| COMMAND (`12eaa5ae`) — `./mvnw -pl runtime-command-consumption-worker -am test` | 1293 | 0 | 0 | 1 |
| READ (`12eaa5ae`) — `./mvnw -pl infra-persistence-read-jdbc -am test` | 354 | 0 | 0 | 0 |
| MONOLITH (`12eaa5ae`) — `./mvnw -pl runtime-monolith -am test` | 1067 | 0 | 0 | 0 |
| ARCHITECTURE (`6835b5c1`) — `./mvnw -pl architecture-tests -am test` | 1551 | 0 | 0 | 1 |

Les décomptes sont par gate, avec recouvrement Maven ; ne pas les additionner comme tests uniques. Les **33 nouvelles méthodes I1** sont toutes exécutées et PASS, sans skip. Le skip historique éventuel est `TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`, optionnel (`step3b.measure` absent) ; il ne devient pas PASS. Les 91 méthodes du manifeste L1, 17 L1-F et 24 L1-F2 sont retrouvées PASS dans les XML frais (inventaires avec recouvrement, détail dans `L1-regression.json`). Le gate documentaire `python3 docs/v3/check_l2_manifest.py`, l'oracle `python3 scripts/v3/check-routing-vectors.py` et `git diff --check` complètent cette preuve.
<!-- /I1_GATE_RESULTS -->

Les oracles ne dépendent pas de l'Engine : [21 vecteurs figés](../testing/evidence/L2-I1/routing-vectors.csv) issus du plan, [recalcul Python standard](../../scripts/v3/check-routing-vectors.py), Java et SQL contre ces littéraux ; catalogue Java vs seed SQL et UUID du trigger ; comparaison `pg_get_functiondef` de **toutes** les fonctions L1 et `pg_get_triggerdef` des triggers avant/après ; contraintes inchangées hors FK supprimée de l'ancien slot ; snapshots externes après commit/rollback. Les fixtures Command SQL complètes ne sont pas une exécution de l'Engine I2. La sentinelle historique hors pilote complète les vrais tests de conservation PENDING/TERMINAL et les régressions L1, sans certifier un déploiement historique.

### Bootstrap administratif reproductible

Ce dossier a exécuté le bootstrap dans les bases PostgreSQL éphémères neuves de test (WRITE et READ dans **deux bases distinctes**), pas sur un environnement utilisateur préexistant. Avant un déploiement pilote, l'administrateur renseigne l'inventaire réel : serveur/port/cluster, base WRITE et READ distinctes, schémas non-public, rôles admin propriétaires et runtime distincts. Les paramètres n'ont aucune valeur par défaut. Préinstaller `pgcrypto` dans `public` sur chaque base.

Appliquer Flyway administrativement avec les placeholders obligatoires `expectedDatabase`, `expectedSchema`, `expectedAdmin`, `expectedRuntime`. WRITE : locations `db/v3-pot/migration`, `db/v3-pot-execution/migration`, **puis** `db/v3-consumption-write/migration` (V1…V5 neufs). READ : uniquement `db/v3-consumption-read/migration`. La migration refuse toute donnée existante ; aucune migration L1/V2 n'est éditée ni supprimée. Les API Flyway exécutables exactes sont exercées dans les tests de bootstrap.

Puis, une fois par autorité, depuis la racine du dépôt, avec serveur/port et identités de l'inventaire :

```sh
PGOPTIONS="-c search_path=<schema_cible>" psql -X -v ON_ERROR_STOP=1   -h <serveur> -p <port> -U <admin> -d <base_cible>   -v authority=WRITE -v expected_database=<base_cible>   -v expected_schema=<schema_cible> -v expected_admin=<admin>   -f scripts/v3/bootstrap-l2-routing.sql
```

Répéter pour READ avec ses identités et `authority=READ`. Pas de transaction inter-bases : le pilote ne démarre que si les deux catalogues sont complets. Le même script est exécuté réellement par `psql` dans les tests distribués/monolithiques ; un rerun échoue, sans réparation implicite.

Activer explicitement `v3-consumption-pilot` et fournir, pour chacune de `pocoma.v3.pilot.write` et `.read`, `url` (avec `currentSchema`), `username`, `password`, `database`, `schema`. Utiliser le rôle runtime associé. Les noms de base/schéma/rôle du pilote sont des identifiants simples `[a-z][a-z0-9_]*` ; connexions TCP requises. Le profil désactive la persistence automatique ; les racines worker et monolithe n'importent aucun traitement hérité dans ce mode. Le redémarrage n'installe ni ne réinitialise rien. Le pilote n'admet ni ne traite de demande en I1.

### Sortie I1 et handoff

<!-- I1_EXIT -->
| Critère de sortie I1 | Résultat et borne |
|---|---|
| Deux cibles V3 neuves isolées | PASS : bases WRITE `test` et READ `pilot_read`, schémas dédiés, sources historiques témoins conservées ; aucun déploiement utilisateur |
| DDL / contrats / partitions | PASS : quatre tables par autorité, 2/7 routes, 8/28 ownership, absence du slot Command dédié sur le pilote |
| Identités et routage | PASS : neuf UUID exacts Java/SQL/trigger, sept familles, 21 vecteurs indépendants, P=4 pilote |
| Bootstrap / concurrence / rollback | PASS : cible et rôle contrôlés sous verrou, deuxième admin bloqué puis refusé, fautes et rerun sans état partiel |
| Privilèges runtime | PASS : SELECT seulement ; DML/DDL, droits hérités et NOINHERIT/SET ROLE dangereux refusés |
| Conservation Command et L1 | PASS local : fonctions/triggers/contraintes préservés, positifs/négatifs SQL et inventaires historiques frais ; pas d'Engine commun certifié |
| Compositions distribuée et monolithique | PASS : démarrage/restart inertes, import ancien direct/indirect et types writers/owners/pollers refusés, deux bases physiques |
| Tests / architecture / provenance | PASS local : quatre gates, 33 méthodes I1 sans skip, XML/logs/catalogues/états/empreintes conservés |
| G1/G2/G3 | FUTURS ; aucun PASS de certification L2 |

**I1 implémenté / PASS local borné.** Le skip exploratoire V2 et les mécanismes absents empêchent toute lecture de ce verdict comme certification globale. Les preuves d'exploitation doivent encore être produites sur une cible réelle explicitement inventoriée lorsqu'un déploiement sera demandé.
<!-- /I1_EXIT -->

Risques résiduels : bootstrap d'exploitation à rattacher à son inventaire réel avant usage ; owner admin de confiance ; aucun SLO ni garantie de plan/coût SQL ; index PENDING à mesurer avec TERMINAL nombreux en I4 ; aucune qualification des leases, claims, retries ou renouvellements encore absents. Les limites L1-F2 restent documentées et ne bloquent pas de nouveau I1.

Étape suivante recommandée : **I2 uniquement sur nouvelle instruction explicite**, à partir du protocole R1/R9 et des garanties L1-C/F/F2 ; prouver owner physique unique, suppression Engine exacte avant suffixe et recovery. **Arrêt après I1 ; I2–I5 non engagés, G1/G2/G3 futurs.**
