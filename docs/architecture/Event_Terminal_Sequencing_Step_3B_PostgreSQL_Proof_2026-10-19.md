# Étape 3B — Preuve PostgreSQL du séquencement terminal des Events

Date : 2026-10-19. **EXPÉRIENCE ISOLÉE / TARGET NON INTÉGRÉ AU RUNTIME.**
Baseline : `21f5b8269357208c8e2c5190397b2c2fefc27e83`, branche `feat/consumption-transaction-protocol`.
Référence : [audit 3A](Event_Terminal_Sequencing_Step_3A_Audit_2026-10-19.md).

## 1. Verdict et portée

**PASS expérimental borné** : A–J ci-dessous vérifient l'attribution atomique, les blocs consécutifs, la réutilisation après rollback, l'ordre total entre familles et les attentes PostgreSQL induites. Aucun deadlock applicatif ajouté par E n'est observé dans les interleavings construits. Ce résultat ne démontre pas l'absence universelle de deadlock.

**INCONCLUSIVE pour l'universalité runtime** : seuls les propriétaires JDBC Spring expérimentaux sont couverts. Aucun changement du propriétaire JPA de production, de Discovery, des migrations, de P → B → S, des retries ou de TA/TE/TR/TH. Les garanties des étapes [2B](Pot_Command_Cas_Consumption_Step_2B_2026-10-18.md) et [2C](Pot_Command_Binding_Shared_Fence_Integration_Step_2C_2026-10-18.md) restent inchangées. Un PASS expérimental n'autorise aucune intégration.

Sources exécutables : [fixture propriétaire et DDL](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/sequencing/TerminalSequencingFixture.java), [scénarios et mesures](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/sequencing/TerminalSequencingPostgresTest.java). Preuves conservées : [observations et résultats](../testing/evidence/Event_Terminal_Sequencing_Step_3B_2026-10-19.txt), [mesures CSV](../testing/evidence/Event_Terminal_Sequencing_Step_3B_2026-10-19.csv).

## 2. Sécurité Git et périmètre déclaré

Avant écriture, inventaire des six worktrees et lecture de leur HEAD/branche ; worktree cible `/private/tmp/pocoma-consumption-transaction-protocol`, propre, HEAD de référence exact, origin `https://github.com/kartaguez/pocoma.git`, divergence `0/0` après fetch. Le fetch initial a été bloqué par l'accès sandbox au fichier Git partagé ; nouvelle exécution autorisée réussie.

| Worktree préexistant | HEAD initial | Conservation |
|---|---|---|
| checkout principal `pocoma` | `90926a5e` / `feat/runtime-monolith` | modifications Keycloak/docs/Bruno, test Expense et documents non suivis préservés ; aucune écriture |
| `pocoma-2c-differential-baseline` | `8a79f06d`, détaché | non touché |
| `pocoma-binding-lock-order-guard` | `2eef3ab9` | non touché |
| `pocoma-consumption-transaction-protocol` | `21f5b826` | seul worktree de livraison |
| `pocoma-expense-context` | `116f4e4a` | non touché |
| `debt-wa6-integration/pocoma` | `2eef3ab9`, détaché | non touché |

Aucun reset, stash, merge, rebase ou checkout. Aucun travail préexistant inclus dans le commit. Règles lues : [AGENTS](../../AGENTS.md), [politique normative](../testing/Reactor_Verification_Policy.md), [contribution](../../CONTRIBUTING.md), [index](../README.md), [gouvernance documentaire](../Documentation_Rationalization_Audit.md#15-gouvernance-documentaire), [validation documentaire](../Documentation_Truth_Validation.md), audit 3A et rapports 2B/2C. La présente preuve ne modifie pas les sources CURRENT F/A/G.

Périmètre annoncé avant implémentation :

- Impact production attendu et observé : **NONE** ; tests/fixtures/documents uniquement.
- Slice primaire : **COMMAND** ; secondaires : **NONE**.
- Preuve locale : PostgreSQL isolé + propriétaires Spring de test ; clôture par la commande canonique COMMAND depuis `app/`.
- Base : schéma **expérimental**, sans Flyway dans cette fixture. Pour la slice existante, bootstrap des tests conservé : politique `Trusted baseline: NONE` ; aucune certification ni exécution historique additionnelle demandée.
- Gate architecture global : **NOT REQUIRED**, aucune modification de dépendance, module ou frontière de production. Dettes globales 2C inchangées.
- Reactor complet : **interdit par la mission, NOT REQUIRED et non exécuté**.
- Impact interdit : runtime, schéma/migration de production, Events existants, Discovery, P/B/S, retries et frontières Consumption.
- Escalade : toute nécessité de modifier ces surfaces aurait imposé une révision préalable ; **aucun franchissement inattendu**.
- Prochain gate global : éventuelle décision d'intégration/Wave ou main selon la politique, hors livraison 3B.

## 3. Schéma expérimental

Schéma privé `experiment`, recréé dans un conteneur jetable ; aucune table de production n'est créée ou modifiée par cette expérience.

```sql
CREATE TABLE experiment.authority (
  family text PRIMARY KEY CHECK (family IN
    ('BUSINESS_EVENT','COMMAND_TERMINAL','BINDING_FACT','USER_CREATED')),
  last_position bigint NOT NULL CHECK (last_position >= 0)
);
-- Une ligne précréée par famille, compteur initial 0.
CREATE TABLE experiment.event (
  family text NOT NULL CHECK (family IN
    ('BUSINESS_EVENT','COMMAND_TERMINAL','BINDING_FACT','USER_CREATED')),
  event_id uuid NOT NULL, payload text NOT NULL,
  PRIMARY KEY (family,event_id)
);
CREATE TABLE experiment.event_position (
  family text NOT NULL,
  position bigint NOT NULL CHECK (position > 0),
  event_id uuid NOT NULL,
  PRIMARY KEY (family,position), UNIQUE (family,event_id),
  FOREIGN KEY (family,event_id) REFERENCES experiment.event(family,event_id)
);
```

Les Events ne portent aucune position. Pot et Expense sont des payloads de **BUSINESS_EVENT**. Le stockage Event est unifié uniquement dans la fixture ; les quatre stockages réels de 3A ne sont pas remaniés. Le collecteur ne produit pas de nouvelle identité lors de l'association : il conserve l'UUID de l'INSERT effectivement réussi.

Aucune FK Event → E : elle provoquerait un verrou implicite E au stade de publication, même sans réservation. Le CHECK de famille suffit ici. Les tests D/E contrôlent l'absence de verrou sur la relation `authority` avant suffixe. La FK EventPosition → Event vise une ligne déjà insérée par la même transaction. Aucune séquence, `nextval`, identité auto-incrémentée ni `ON CONFLICT DO NOTHING`.

Les fixtures P/B/S sont trois tables de lignes indépendantes : P et S modifiés par UPDATE, B pris `FOR SHARE`. Elles reproduisent les modes de verrou et leur ordre utile au scénario I, sans prétendre rejouer les validations, CAS, temporalité, provenance, claims et contraintes de la vraie Command.

## 4. Propriétaire physique, collecte et suffixe exact

Le gestionnaire expérimental dérive de `DataSourceTransactionManager`. Chaque `doBegin` physique installe automatiquement une collecte dans les ressources transactionnelles Spring, indexée par une clé propre au gestionnaire. Les participants REQUIRED retrouvent la même collecte. Il n'y a ni ThreadLocal applicatif indépendant ni registre durable supplémentaire. Le collecteur est vidé et détaché dans `doCleanupAfterCompletion`, en commit comme en rollback ; ses hooks et écritures différées sont aussi libérés. Une référence conservée par le test reste fermée et vide.

L'adaptateur expérimental publie d'abord l'INSERT Event synchrone, puis enregistre `(family, UUID)`. Il refuse tout appel hors propriétaire ou après fermeture. Les opérations métier de la fixture vérifient aussi cette fermeture. Le collecteur est une liste transitoire d'identités, **pas une seconde source de vérité durable**.

Au retour du travail métier et des finalisations :

1. `prepareForCommit` agit **uniquement si `status.isNewTransaction()`**.
2. Exécuter les écritures différées enregistrées : barrière de flush explicite, avec SQL réel S et publication Binding dans J.
3. Fermer la collecte. Vide → aucun SQL E ni association.
4. Parcourir le même ordre total explicite : **BUSINESS_EVENT → COMMAND_TERMINAL → BINDING_FACT → USER_CREATED**.
5. Pour chaque famille non vide, trier les UUID par leur représentation canonique texte croissante, indépendante de l'ordre de production et de la collation PostgreSQL.
6. Réserver exactement N par cet UPDATE transactionnel :

```sql
UPDATE experiment.authority
SET last_position = last_position + :n
WHERE family = :family
RETURNING last_position;
-- Pour le résultat r : associations r-n+1, ..., r dans l'ordre UUID.
INSERT INTO experiment.event_position VALUES (:family, :position, :existing_event_id);
```

7. Terminer toutes les associations synchrones et committer sur la **même connexion et le même xid physique**. Le test vérifie PID/xid au début et juste avant le commit. Ces SELECT de diagnostic sont la seule instrumentation SQL supplémentaire sous E, outre les sondes/hooks de scénarios.

Le suffixe n'effectue ni validation métier, ni opération P/B/S, ni nouvelle publication. Les hooks servent uniquement aux barrières, sondes et injections d'échec des tests ; ils ne sont pas une proposition d'extension runtime. Un échec du suffixe remonte au gestionnaire et provoque le rollback de l'ensemble, sans retry interne.

**Piège Spring constaté pendant la mise au point :** `prepareForCommit` est également appelé pour un participant REQUIRED. Une première fixture sans garde exécutait deux suffixes et échouait à l'unicité EventPosition. La garde physique ci-dessus et le test H protègent désormais ce point. Ce défaut corrigé de fixture ne constitue pas un contre-exemple au protocole terminal retenu.

### Savepoints : interdiction contractuelle dans cette variante

`PROPAGATION_NESTED` est refusée par le gestionnaire ; aucune suspension/REQUIRES_NEW n'est autorisée. Une liste mémoire ne connaît pas un rollback partiel JDBC. F injecte volontairement un savepoint JDBC brut, publie un Event puis annule la ligne au savepoint : le collecteur garde l'UUID, la FK d'association échoue, **toute la transaction échoue**, compteur inclus. Cela démontre le danger et le comportement fermé de cet exemple, pas un support des savepoints. Les savepoints bruts doivent être interdits par contrat aux participants ; la fixture ne prétend pas intercepter tous les accès JDBC. Une intégration voulant les autoriser devra réconcilier la collecte aux savepoints, ou la reconstruire depuis une vérité transactionnelle durable.

## 5. Matrice des preuves

Les attentes reposent sur des connexions/PID distincts, des barrières `CountDownLatch`, des futurs bornés et des observations SQL. **Aucun `Thread.sleep`**. Les timeouts bornent un échec de test ; ils ne fabriquent pas l'ordre. Les sondes répètent des SELECT jusqu'à l'état PostgreSQL demandé.

| Scénario | Statut | Preuve et résultat | Limite |
|---|---|---|---|
| A — démarrage inversé | **PASS** | TE1 obtient le premier xid et publie Pot ; TE2 publie Expense, prend E et commit ; TE2 reçoit 1, TE1 reçoit 2 après libération de la barrière ; Events et compteurs exacts | ordre par famille ; pas un ordre global entre familles disjointes |
| B — E tenue | **PASS** | TE2 attend réellement l'UPDATE E de TE1 ; `pg_blocking_pids` identifie TE1 et `wait_event_type=Lock` ; progression après commit, positions 1 puis 2 | attente construite, pas une borne de latence |
| C — rollback | **PASS** | attente identique à B ; TE1 réserve et insère ses associations puis lève une exception ; TE2 réutilise 1 ; Event annulé absent, une seule association et compteur 1 | rollback normal confirmé, pas de crash réseau/commit inconnu |
| D — plusieurs Events | **PASS** | UUID publiés 3,1,2 ; positions respectives 3,1,2 ; exactement trois associations, compteur 3 ; aucun verrou E avant suffixe | ordre choisi explicitement, sans signification métier |
| E — familles opposées | **PASS** | deux transactions publient quatre familles dans des ordres opposés ; TE2 attend la première E de TE1 ; traces d'acquisition identiques dans l'ordre total ; deux positions par famille, tous commits terminés | absence du cycle E1/E2 dans cet interleaving ; pas d'universalité sur writers externes |
| F — échec après réservation | **PASS** | première famille associée, seconde réservée puis UUID inexistant injecté : FK échoue ; P, tous Events, associations et compteurs rollbackés ; transaction suivante reprend 1 | injection FK ; pas toutes les classes possibles d'erreur de commit |
| F — savepoints | **PASS (contrat d'interdiction)** | NESTED refusé ; contournement JDBC brut rend la collecte stale et conduit au rollback global par FK | savepoints arbitraires non supportés ; interdiction à imposer avant intégration |
| G — zéro publication | **PASS** | une autre connexion tient toutes les E `FOR UPDATE` ; transaction sans Event commit sans attendre ; sonde sous propriétaire avant commit : aucun verrou de relation E, compteurs inchangés | transactions vides de cette fixture |
| H — cycle de vie | **PASS** | quatre transactions sur le même thread, dont exception ; collections distinctes, vidées, fermées et désindexées ; participants REQUIRED partagent une collecte sans suffixe anticipé ; aucune fuite UUID ; publication/métier tardifs refusés | aucune suspension autorisée, pas de propagation asynchrone du contexte |
| I — interaction P/B/S | **PASS** | Command représentative tient P puis B SHARE puis S et attend E ; trois autres PID attendent respectivement P, B exclusif et S ; chaque lien est observé dans PostgreSQL ; détenteur E sans bloqueur ; tous progressent après commit ; aucune P/B/S après E | fixture de modes de verrou, pas intégration du vrai protocole Command |
| J — runner / annotation | **PASS borné** | `SpringTransactionRunner` réel et proxy Spring `@Transactional` réel partagent le gestionnaire expérimental ; SQL serveur : Events/S différé avant E, associations avant COMMIT ; PID/xid inchangés | DataSourceTransactionManager de test, pas JpaTransactionManager de production |
| J — owner Consumption | **PASS borné** | wrapper `ConsumptionOwnerTransactionRunner` réel : succès durable confirmé ; échec du suffixe signalé phase COMMIT / outcome ROLLED_BACK avec aucun effet partiel | aucun scénario commit UNKNOWN ajouté ; contrats existants conservés |
| J — callback tardif | **PASS (refus)** | publication dans `beforeCommit` après fermeture rejetée ; toute la transaction rollbackée, compteur inchangé | SQL brut ou flush JPA échappant aux adaptateurs non empêché |
| Universalité Spring/runtime | **INCONCLUSIVE** | capacité automatique prouvée pour chaque transaction physique ouverte par le gestionnaire expérimental, même sans runner | ni tous les gestionnaires, ni Hibernate/listeners, ni tous les producteurs réels ne sont couverts |

## 6. Observations SQL et verrous

Sonde utilisée pour chaque arête attendue :

```sql
SELECT pid, backend_xid, wait_event_type, wait_event, query,
       pg_blocking_pids(pid) AS blockers
FROM pg_stat_activity
WHERE pid = :waiting
  AND :holding = ANY(pg_blocking_pids(pid))
  AND wait_event_type = 'Lock';
```

Les preuves liées contiennent les PID, xid, statements réellement bloqués et bloqueurs. Les attentes E observées sont des `Lock/transactionid` sur l'UPDATE du compteur ; I montre aussi les UPDATE P/B/S bloqués derrière le Command qui attend E. Ce ne sont pas des assertions déduites de l'état d'un thread Java.

J active `SET LOCAL log_statement='all'` uniquement sur les deux transactions comparées. Les logs serveur sont filtrés par PID et contrôlés par le test : dernier INSERT Event et UPDATE S avant premier UPDATE authority ; dernier INSERT EventPosition avant COMMIT. L'instrumentation Java associe ces statements au même xid et vérifie qu'après première E il ne reste que E/associations/commit. Les mesures n'activent pas ce logging.

G contrôle les verrous pendant la transaction vide de publication, après le suffixe, pas seulement après libération au commit. D/E contrôlent aussi le préfixe après publication. Le compteur est une ligne transactionnelle mise à jour, donc le verrou conserve sa sérialisation jusqu'au commit ou rollback ; une réservation annulée n'avance pas la prochaine position visible.

Graphe construit : `P → B(SHARE) → S → E_business → E_terminal → ... → COMMIT`. Les suiveurs ajoutent `attente P/B/S → Command attendant E`. L'ordre commun des E empêche le cycle inversé entre familles dans E. Le détenteur terminal E n'a plus de demande applicative vers P/B/S. La contention se propage cependant vers ces ressources pendant l'attente E ; un suffixe court ne supprime pas cette rétention.

**Limites de deadlock :** aucun SQLSTATE 40P01 ni cycle ajouté dans les scénarios exécutés. La preuve n'exclut pas les cycles préexistants du préfixe, les writers contournant E, une DDL concurrente, des contraintes/listeners différés, des FK vers des parents concurrents ou du SQL métier brut après E. Les deux unicités ne suffisent pas, seules, à garantir la continuité contre des writers arbitraires : celle-ci repose sur le protocole commun et le rollback intégral.

## 7. Mesures exploratoires

### Environnement et méthode

Hôte Apple M4, 10 CPU logiques, 16 Gio RAM ; Java Temurin 21+35. Docker Desktop, moteur 29.4.0, Linux aarch64, 10 CPU visibles et 8 321 712 128 octets de RAM affectés. Conteneur `postgres:17-alpine`, version observée **PostgreSQL 17.11**, GCC Alpine 15.2.0 ; `fsync=on`, `synchronous_commit=on`, isolation READ COMMITTED. Accès JDBC via localhost et port Docker ; stockage jetable Docker, pas un volume/serveur de production.

Plan : concurrence **1 et 4** ; **1 et 8 Events par famille et par transaction** ; **1 et 2 familles** communes (BusinessEvent + CommandTerminal). Chaque transaction a ses propres UUID et ne partage aucune P/B/S. Pour chacune des huit configurations : trois répétitions appariées sans/avec E, ordre alterné entre répétitions. Même conteneur, schéma, client, préfixe et flush ; variante sans E conserve les INSERT Events et le collecteur mais saute réservation et associations. Donc le delta mesure **E + EventPosition + contraintes/index + commit supplémentaire associé**, pas le seul verrou E.

Par worker et variante : 8 transactions de chauffe, puis 40 mesurées ; barrière commune après chauffe. Connexion physique neuve par transaction, sans pool ; acquisition de connexion incluse dans la latence totale. Le débit couvre uniquement la phase mesurée après barrière jusqu'au retour des commits. Statistiques par répétition : moyenne, médiane, p95 au rang supérieur, maximum et écart-type population. **48 observations agrégées**, 4 800 transactions mesurées au total, auxquelles s'ajoute la chauffe. Compteurs, cardinalités et continuité sont contrôlés après chaque lot.

Définitions :

- **Attente/acquisition E** : somme des durées client de chaque UPDATE RETURNING, de l'appel à sa réponse. Inclut l'attente réelle éventuelle, l'exécution SQL et l'aller-retour ; **pas une mesure pure du lock wait**. Valeur non nulle même à concurrence 1.
- **Rétention E** : durée depuis le retour du premier UPDATE E jusqu'au retour du commit JDBC. Inclut autres E, associations, contrôles PID/xid et commit ; exclut le trajet retour de la première acquisition avant son horodatage. Approximation instrumentée de la section critique, pas horodatage serveur du déverrouillage.
- **Latence** : début d'appel runner → retour après commit/nettoyage, connexion comprise.

### Résultats

Débits : étendue des trois répétitions ; autres colonnes : moyenne des trois statistiques par répétition (le p95 affiché n’est donc pas un p95 regroupé).

| Concurrence | Events/famille | Familles | Débit sans E (tx/s) | Débit avec E (tx/s) | Latence moyenne sans/avec (ms) | Acquisition E moyenne (ms) | Rétention E moyenne (ms) | p95 latence avec E (ms) |
|---|---|---|---|---|---|---|---|---|
| 1 | 1 | 1 | 277–320 | 223–233 | 3.31 / 4.37 | 0.65 | 0.80 | 4.88 |
| 1 | 1 | 2 | 286–318 | 212–216 | 3.37 / 4.67 | 0.76 | 1.05 | 5.20 |
| 1 | 8 | 1 | 257–264 | 172–178 | 3.83 / 5.73 | 0.59 | 1.61 | 6.15 |
| 1 | 8 | 2 | 213–220 | 132–134 | 4.64 / 7.50 | 0.71 | 2.60 | 7.99 |
| 4 | 1 | 1 | 811–901 | 591–709 | 4.64 / 6.14 | 1.07 | 1.21 | 7.91 |
| 4 | 1 | 2 | 855–871 | 458–594 | 4.60 / 7.35 | 1.80 | 1.73 | 10.22 |
| 4 | 8 | 1 | 713–737 | 412–447 | 5.42 / 9.30 | 2.55 | 2.24 | 13.03 |
| 4 | 8 | 2 | 558–615 | 310–340 | 6.81 / 12.35 | 4.51 | 3.03 | 15.69 |

Dans cette campagne, à concurrence 4 et huit Events dans deux familles, le débit avec E est de 310–340 tx/s contre 558–615 sans E ; l'acquisition cumulée moyenne atteint 4,51 ms, contre 0,71 ms à concurrence 1 pour la même cardinalité. La rétention moyenne est respectivement 3,03 et 2,60 ms. L'augmentation du lot et du nombre de familles prolonge les associations sous E ; la contention s'ajoute à ce travail. Ces observations ne séparent pas le coût du verrou des index, FK, allers-retours et commits.

Les valeurs détaillées des 48 répétitions, dont médianes, maxima et écarts-types, figurent dans le CSV. Les variations entre répétitions sont conservées ; aucune élimination d'outlier ni seuil d'acceptabilité.

Ces petites charges fermées explorent un coût et une contention dans cet environnement. Elles ne recherchent ni saturation maximale, ni capacité soutenue, ni distribution de charge de production. L'ouverture fréquente des connexions, Docker/VM, le scheduling JVM, JIT/GC, la chauffe courte, les tables petites et l'absence des vrais travaux métier limitent la représentativité. Aucun SLO ou benchmark de production n'est déduit. `fsync=on` n'est pas une preuve de résistance à un crash matériel ; aucun test de crash n'a été exécuté.

## 8. Options d'intégration et conditions restantes

| Option | Évaluation issue de la preuve |
|---|---|
| suffixe dans un adaptateur de publication | rejeté : trop tôt, finalisations et P/B/S encore possibles ; l'adaptateur collecte seulement |
| suffixe au retour de chaque runner | insuffisant : un runner peut être participant ; ne couvre pas une transaction ouverte par annotation |
| callback `beforeCommit` de collecte | à encadrer : ordre des callbacks et flush Hibernate peut laisser du métier après E ; aucune universalité prouvée |
| capacité dans le gestionnaire/propriétaire physique | faisable dans le gestionnaire JDBC de test ; couvre runner et annotation si tous utilisent ce gestionnaire ; garde `isNewTransaction` indispensable |
| adaptation du propriétaire JPA de production | **non expérimentée**, exige barrière réelle EntityManager/Hibernate et preuve du SQL restant au commit |

Avant toute intégration runtime : décider le contrat global de propriétaire, interdire ou supporter les savepoints ; couvrir tous les adaptateurs d'émission identifiés en 3A et leurs vrais UUID, dont ports auxiliaires, Registration, recovery et FAILED ; démontrer les frontières physiques JPA/JDBC et le flush après toutes finalisations ; empêcher les publications/SQL métier tardifs et auditer les synchronisations/listeners ; préserver le traitement COMMITTED/ROLLED_BACK/UNKNOWN sans replay indu ; définir bootstrap/cutover/backfill et accès autorisés à E/EventPosition ; élargir les preuves seulement aux slices réellement modifiées ; mesurer les charges représentatives avant toute décision de capacité. Aucun de ces travaux n'est commencé ici.

## 9. Vérifications et livraison

Commandes de vérification exécutées depuis `app/` :

```bash
# Retours ciblés de mise au point, puis version fsync=on :
./mvnw -pl runtime-command-consumption-worker -am -Dtest=TerminalSequencingPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
# Campagne finale de correction + mesures :
./mvnw -pl runtime-command-consumption-worker -am -Dtest=TerminalSequencingPostgresTest -Dsurefire.failIfNoSpecifiedTests=false -Dstep3b.measure=true test
# Clôture canonique :
./mvnw -pl runtime-command-consumption-worker -am test
```

- Campagne finale isolée : **14 tests, 0 échec, 0 erreur, 0 ignoré**, dont 13 de correction et 1 campagne de mesures ; BUILD SUCCESS en 22,519 s.
- Slice canonique COMMAND : **995 tests, 0 échec, 0 erreur, 1 ignoré**, 192 classes / fermeture Maven de 51 projets, BUILD SUCCESS en 1 min 52 s. L'unique test ignoré est la campagne exploratoire, activée et passée séparément ci-dessus.
- Les tests `PotCasConsumptionPostgresTest` et `BindingSharedFencePostgresTest` passent dans cette même commande ; la fermeture inclut les preuves existantes et leurs bootstraps, sans exécuter les runtimes des autres slices.
- Vérification documentaire : liens locaux du rapport et de l'index résolus ; `git diff --check`, revue du diff exact et contrôle des chemins livrés.
- Impact production observé **NONE**, slices déclarées **COMMAND / secondaire NONE**, franchissement inattendu **NONE**, gate architecture global **NOT REQUIRED / non exécuté**, reactor complet **NOT REQUIRED / non exécuté**. Aucun gate historique additionnel ni migration de production nouvelle.


Les essais de mise au point ne sont pas promus en preuves finales : lancement initial sans test à cause d'un chemin de fichier erroné ; accès Docker sandbox refusé puis exécution autorisée ; échec de fixture sur participant REQUIRED corrigé ; première mesure avec FK Event→E abandonnée après revue ; résultats conservés issus du schéma final sans FK vers E et avec fsync actif. Aucun élargissement de slice et aucune modification opportuniste du gate global.

Livraison : commit ne contenant que les deux sources de test et les documents/preuves listés ; push non forcé sur la branche cible ; le SHA final est le commit portant ce rapport (voir la réponse de livraison et `git log -1`). Contrôle final HEAD/origin, divergence et propreté effectué après push. Aucune intégration runtime ne suit cette livraison.
