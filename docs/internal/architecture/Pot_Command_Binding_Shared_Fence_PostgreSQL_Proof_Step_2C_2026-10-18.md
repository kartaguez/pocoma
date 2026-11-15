# Étape 2C — Preuve PostgreSQL du fence Binding partagé

Suite : [intégration dans TE](Pot_Command_Binding_Shared_Fence_Integration_Step_2C_2026-10-18.md). Ce rapport conserve la preuve expérimentale et sa baseline historique.

Date : 2026-10-18. Verdict expérimental : **PASS** pour le protocole candidat et les interleavings décrits ci-dessous. **L'équivalence de bout en bout dans TE n'est pas établie** : aucun fence de production n'est remplacé.

## Baseline Git et périmètre

Branche : `feat/consumption-transaction-protocol`. HEAD initial attendu et constaté : `6e971b5a1acaf9105cad350cfe4a002454359de9`. Origin : `https://github.com/kartaguez/pocoma.git`. Après `git fetch origin`, HEAD et origin sont identiques, divergence **0/0**. Worktree cible initialement propre : `/private/tmp/pocoma-consumption-transaction-protocol`.

`git worktree list` a aussi identifié le checkout principal `feat/runtime-monolith` à `90926a5e`, avec du travail préexistant Keycloak, documentation, Bruno et fichiers non suivis, ainsi que les worktrees `fix/binding-lock-order-guard` à `2eef3ab9`, `fix/expense-context-version-coherence` à `116f4e4a` et le checkout détaché `debt-wa6-integration` à `2eef3ab9`. Aucun checkout ni contenu préexistant n'est modifié. Aucun reset, stash, checkout destructif ni push forcé.

Politique normative lue : [Reactor_Verification_Policy](../testing/Reactor_Verification_Policy.md). Référence de continuité : [étape 2B](Pot_Command_Cas_Consumption_Step_2B_2026-10-18.md).

Déclaration antérieure à l'implémentation :

- Impact production attendu et observé : **NONE**. Uniquement une nouvelle classe de tests COMMAND et ce rapport.
- Slice primaire : **COMMAND**. Secondaires : **NONE**. Le lifecycle BINDING est un collaborateur existant exercé par COMMAND ; son comportement n'est pas modifié.
- Commande canonique depuis `app/` : `./mvnw -pl runtime-command-consumption-worker -am test`.
- Preuve locale : PostgreSQL réel, transactions physiques distinctes, lectures READ COMMITTED, rollback, recovery et finalisation Consumption existants.
- Interdits : runtime, contrats, schéma, migrations, POM, ordre P → B → S et comportement des autres slices.
- Gate global architecture : **NOT REQUIRED**, non exécuté. Reactor complet : **NOT REQUIRED**, non exécuté. Aucune restructuration ni clôture de Wave/intégration main.
- Base : **CURRENT**, bootstrap Testcontainers/Flyway existant. La politique ne certifie aucune baseline ; les migrations déjà incluses dans cette slice restent exécutées, sans gate historique supplémentaire.
- Conditions d'escalade : besoin de changer un contrat ou un runtime ; signaler avant tout élargissement. Franchissement inattendu : **NONE**.

## Protocole actuel et candidat

Le fence actuel est `ExternalIdentityBindingStreamJdbcRepository.fenceExact`, un UPDATE conditionnel sans changement de valeur. Il protège la ligne permanente `(issuer, subject)` avec un verrou de ligne `FOR NO KEY UPDATE` ; deux fences actuels se sérialisent sur cette identité. Le lifecycle acquiert explicitement `FOR UPDATE`, puis vérifie/modifie l'autorité active, avance la révision et ajoute le fait dans la même transaction.

Le candidat, implémenté uniquement dans les helpers de test, acquiert inconditionnellement la ligne permanente par `FOR SHARE`, puis effectue **un second statement** de validation exacte. Le verrou reste détenu jusqu'au commit/rollback physique. Aucun commit intermédiaire ni savepoint libérant B. Le candidat retourne vrai seulement si révision, utilisateur et occurrence correspondent simultanément.

Le test ne remplace aucun bean Binding, ne modifie aucun repository et n'installe aucun protocole alternatif dans l'application. Les callbacks expérimentaux F/G passent par le vrai owner COMMAND ; le SQL candidat demeure dans la classe de test.

## SQL testé

Les deux statements candidats sont exécutés sur **la même connexion et dans la même transaction READ COMMITTED** :

```sql
-- Statement 1 : paramètres issuer, subject ; aucun filtre de révision ni jointure.
select current_revision
from external_identity_binding_streams
where issuer=? and subject=?
for share;

-- Statement 2 : nouvelle lecture après acquisition du verrou.
-- Paramètres issuer, subject, observedRevision, userId, bindingId.
select count(*)
from external_identity_binding_streams s
join external_identities a on a.issuer=s.issuer and a.subject=s.subject
where s.issuer=? and s.subject=? and s.current_revision=?
  and a.user_id=? and a.binding_id=?;
-- Validation si et seulement si count = 1.
```

Le verrou seul ne valide pas l'autorité. Un résultat vide de la jointure ou une révision différente rejette l'observation. Le stream doit exister : les fixtures le créent via le vrai acquire ; le cas stream manquant n'est pas une preuve de création concurrente.

Le lifecycle réellement appelé est `JpaExternalIdentityBindingAdapter.acquire/detach`, et ses repositories exécutent notamment :

```sql
select issuer, subject, current_revision
from external_identity_binding_streams
where issuer=? and subject=? for update;

delete from external_identities
where issuer=? and subject=? and binding_id=?;

update external_identity_binding_streams set current_revision=?
where issuer=? and subject=? and current_revision=?;

-- Reattach : occurrence nouvelle réservée, puis autorité active insérée.
insert into external_identities (issuer,subject,user_id,binding_id)
values (?,?,?,?) on conflict (issuer,subject) do nothing;
```

Ces appels ajoutent aussi les vrais faits attach/detach et réservent les occurrences. Aucun SQL de lifecycle simplifié ne se substitue à eux.

Pour comparaison, voici le SQL actuel conservé dans le runtime, lu dans les sources et exercé par les tests existants de la slice ; il n'est pas le helper candidat :

```sql
update external_identity_binding_streams as s
set current_revision=s.current_revision
where s.issuer=? and s.subject=? and s.current_revision=?
  and exists (select 1 from external_identities a
              where a.issuer=s.issuer and a.subject=s.subject
                and a.user_id=? and a.binding_id=?);
```

Les observations utilisent `pg_backend_pid()`, `txid_current()`, `show transaction_isolation` et :

```sql
select ? = any(pg_blocking_pids(?)); -- blocker, waiter
select cardinality(pg_blocking_pids(?));
```

Les mutations témoins sont de vrais UPDATE de `pot_global_versions`, avec Pots distincts dans A. G ajoute de vrais `command_outcomes` et `command_terminal_events` via le port de publication existant, avant la finalisation réelle Consumption. Ce ne sont pas des simulations en mémoire.

## Modes et compatibilité

Matrice des verrous **de ligne** PostgreSQL : ✓ compatible, × conflit entre transactions distinctes. Elle décrit les modes PostgreSQL, pas quatre types de fences applicatifs nouvellement implémentés.

| Demande / détenu | KEY SHARE | SHARE | NO KEY UPDATE | UPDATE |
|---|---|---|---|---|
| KEY SHARE | ✓ | ✓ | ✓ | × |
| SHARE | ✓ | ✓ | × | × |
| NO KEY UPDATE | ✓ | × | × | × |
| UPDATE | × | × | × | × |

La coexistence SHARE/SHARE et le conflit SHARE/UPDATE sont directement exercés ici. Le reste de la matrice et le mode implicite du no-op UPDATE reposent sur la [documentation officielle PostgreSQL 17](https://www.postgresql.org/docs/17/explicit-locking.html), et ne sont pas présentés comme une matrice intégralement mesurée. Les verrous relationnels `ROW SHARE` des SELECT verrouillants ne doivent pas être confondus avec ces modes de ligne. `FOR KEY SHARE` ne constitue pas un substitut général au candidat : sa compatibilité avec NO KEY UPDATE rendrait la protection insuffisante face à un writer utilisant ce mode.

## Scénarios et invariants

Classe : [BindingSharedFencePostgresTest](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/BindingSharedFencePostgresTest.java).

| Scénario | Exécutions par passe | Résultat / état durable vérifié |
|---|---:|---|
| A — deux Pots, même Binding | 5 | Deux PID et XID différents ; les deux fences réussissent avant tout commit. Les deux versions restent invisibles à l'observateur ; commit A ne publie que P1, puis commit B publie P2. Aucun bloqueur des deux commandes. |
| B — detach derrière deux fences | 5 | Le vrai detach attend A, puis attend encore B après commit A. B rollbacke ; detach termine seulement ensuite. Autorité absente, révision 2, deux faits. |
| C — detach gagnant | 1 | Après detach committé, ni l'ancienne révision 1 ni la révision actuelle 2 avec l'ancien BindingId ne valident l'autorité absente. État final détaché et deux faits. |
| D — commande attendant detach | 5 | Ancienne observation valide ; detach non committé détient le verrou exclusif ; attente du SHARE constatée par PID. Après commit, le verrou retourne révision 2 et le statement suivant rejette l'ancienne occurrence, même en présentant la révision 2. |
| E — detach/reattach, même utilisateur | 1 | Nouveau BindingId, révision 3. Ancien couple rejeté ; ancien BindingId avec nouvelle révision rejeté ; nouveau BindingId avec ancienne révision rejeté ; mauvais userId rejeté ; seul le nouveau triplet valide. Trois faits, deux occurrences et autorité active exacte. |
| F1 — rollback | 5 | Detach réellement bloqué derrière B, erreur de commande, rollback physique, version témoin restaurée à 1 ; detach progresse et committe révision 2 sans autorité active. |
| F2 — recovery | 1 (deux branches) | Vraie recovery après rollback confirmé. Occurrence détachée → rejet métier durable CALLER_IDENTITY_NOT_CURRENT, un outcome et un terminal event dans une autre transaction. Occurrence toujours active mais observation périmée → BindingFenceConflictException classifiée BINDING_FENCE_CONFLICT / TRANSIENT, aucune publication terminale, claim encore actif. Version témoin restaurée dans les deux branches. |
| G — takeover et CAS S perdant sous B | 5 | Vrai takeover pendant la détention de B ; detach bloqué sur la commande ; reprise → LostClaimException du CAS S existant, rollback confirmé. Version revenue à 1, aucun outcome ni terminal event du perdant ; claim gagnant toujours courant/non terminé, ancien claim invalidé ; detach libéré et committé. |

Total : **28 exécutions JUnit**, huit méthodes de scénarios. Les deux branches F2 sont dans une même exécution JUnit. Les cinq scénarios sensibles A/B/D/F1/G utilisent chacun `@RepeatedTest(5)`.

## Preuves de concurrence et fraîcheur

A/B ouvrent des connexions JDBC indépendantes, sans transaction de test englobante. PID et XID sont comparés explicitement. Chaque session désactive autocommit et impose READ COMMITTED. F/G utilisent le runner transactionnel COMMAND et les proxies Spring existants. Les transactions lancées sur des threads distincts possèdent des contextes transactionnels distincts.

Une absence de commit suivie du retour des deux fences dans A prouve la coexistence : le retour de B n'est pas obtenu en résolvant A. B est une preuve supplémentaire de maintien effectif des deux protections : le detach n'avance pas après la seule résolution de A. `pg_blocking_pids` observe le backend attendu, plutôt qu'un délai supposé. Les latches ordonnent D et G ; les `Future.get` fournissent les relations de visibilité et les échéances de détection d'échec. Aucun `Thread.sleep`. La boucle d'observation SQL est bornée à dix secondes ; elle ne fabrique pas les interleavings. Les sessions JDBC imposent aussi un statement_timeout de douze secondes.

D établit explicitement une ancienne lecture avant le detach, puis démarre le SELECT verrouillant pendant que le detach détient son verrou et ses modifications non committées. La connexion d'observation voit encore l'ancien état ; la commande est prouvée bloquée par le backend lifecycle. Après libération, le SELECT verrouillant retourne la révision avancée et le **statement séparé** voit la suppression active committée. Le candidat ne dépend donc pas de la fraîcheur d'une sous-requête démarrée avant l'attente.

Cela ne justifie pas de fusionner les deux statements. Le test historique `BindingOptimisticCasSemanticsPostgresTest`, conservé, caractérise déjà le risque d'une sous-requête gardant son ancien snapshot pendant la réévaluation de la ligne cible. Les assertions de D prouvent la forme à deux statements sur ce detach ; elles n'énumèrent pas toutes les formes possibles de SELECT avec jointures. Aucun résultat n'est étendu à REPEATABLE READ ou SERIALIZABLE.

## Lifecycle, recovery et Consumption

Le lifecycle garde son exclusivité. Le candidat présuppose que **tous** les writers de l'autorité active acquièrent le stream exclusif avant mutation, et que la ligne permanente n'est pas supprimée/recréée. La réservation d'occurrences et la révision croissante restent les protections contre la réutilisation d'une identité au même utilisateur.

F2 appelle le vrai `commandConsumptionExecute`, qui contient `BindingFenceRecoveryExecuteUseCase`. La branche technique utilise intentionnellement une observation de révision 0 face à une occurrence active à 1 ; elle ne prétend pas qu'un changement lifecycle ordinaire conserve le même BindingId. La branche métier présente l'observation 1 après detach. Les deux échouent par le SQL candidat, puis lèvent le type existant BindingFenceLostException dans le callback de test. Le runner confirme ROLLED_BACK via afterCompletion ; la recovery utilise son vrai lookup et son vrai participant. Le xmin de l'outcome rejeté diffère du XID de TE échouée. La classification TRANSIENT est testée ; la planification TH/retry complète n'est pas réimplémentée ici et reste couverte par les tests existants de la slice.

G n'injecte pas un CAS perdant : `lifecycle.acquire` invalide réellement le claim expiré et installe le nouveau à l'instant de fin de lease fourni. L'horloge métier d'acquisition permet ce takeover déterministe, sans attendre trente secondes. La commande détient toujours B mais ne détient pas encore S ; le takeover peut donc committe. Le vrai participant finalise ensuite avec l'ancien claim et perd son UPDATE conditionnel S. Son rollback annule le compteur et les publications déjà effectuées. Le detach ne progresse qu'après cette résolution.

## Limites et risques résiduels

- Les callbacks A/F/G sont expérimentaux. A met à jour des compteurs de Pots distincts ; F/G exercent le runner, la recovery et la finalisation réels avec des effets témoins. Ils ne rejouent pas toute la factory Pot, ses écritures temporelles et son append BusinessEvent avec le nouveau fence.
- Le runtime conserve son fence UPDATE. La passe COMMAND vérifie ses tests existants, mais ne démontre pas un chemin Pot de production exécutant SHARE. L'intégration future devra préserver la même connexion, la lecture fraîche, la propagation transactionnelle et la durée du verrou jusqu'à la résolution de TE.
- Les ordres sont imposés par les barrières pour les scénarios annoncés. La répétition détecte certaines instabilités ; elle ne constitue ni une exploration exhaustive des interleavings ni une preuve formelle.
- Aucune mesure de débit, fairness, starvation lifecycle sous arrivée continue de commandes, coût MultiXact/WAL ou comportement en forte contention. Aucun engagement de gain de performance.
- Aucune preuve nouvelle sur crash, partition réseau, issue de commit UNKNOWN, failover, DDL concurrente, suppression de stream, writer externe violant le protocole ou isolation différente.
- SHARE conserve un verrou jusqu'à la résolution ; un changement ultérieur demandant de promouvoir cette même protection en verrou exclusif peut créer des cycles. Le candidat ne doit pas conduire une commande à exécuter un lifecycle sous son fence partagé sans nouvelle analyse.

Verdict : **PASS expérimental**, aucun échec des garanties demandées dans les scénarios exécutés. L'adoption en production reste une décision architecturale et une intégration TE distinctes ; aucun correctif ou changement de protocole n'est livré.

## Exécution et livraison

Environnement réellement observé : PostgreSQL **17.11**, image Testcontainers `postgres:17-alpine`, Java 21. Les numéros mineurs du tag ne sont pas figés pour une réexécution future.

Commandes Maven effectivement utilisées, depuis `app/` (répétées pendant la préparation) :

```bash
./mvnw -pl runtime-command-consumption-worker -am test -Dtest=BindingSharedFencePostgresTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl runtime-command-consumption-worker -am test
```

| Passe réussie | Fin locale Europe/Paris | Résultat exact |
|---|---|---|
| Première preuve ciblée | 2026-10-18 22:34:11 +02:00 | 28 tests, 0 échec, 0 erreur, 0 skip ; 11,861 s Maven |
| Canonique COMMAND, assertions finales | 2026-10-18 22:36:51 +02:00 | **964 tests, 191 classes, 0 échec, 0 erreur, 0 skip**, 51 modules ; 1 min 49 s Maven |
| Répétition ciblée finale | 2026-10-18 22:37:22 +02:00 | **28 tests, 0 échec, 0 erreur, 0 skip** ; 10,133 s Maven |

Les comptes de la passe canonique sont la somme des rapports par classe, sans compter deux fois les résumés de modules. Les 28 scénarios sont inclus dans ses 964 tests ; les passes ciblées ne sont pas additionnées pour annoncer un nombre de tests distincts. Sur les deux passes de la version finale, chaque scénario concurrent sensible a donc dix répétitions réussies.

Préparation : un chemin de création relatif erroné a d'abord empêché l'écriture du fichier ; une compilation a ensuite révélé `TerminalReason.code()` au lieu de `value()`. Les invocations sans accès Docker depuis la sandbox ont échoué avant les preuves PostgreSQL concernées. Les passes réussies utilisent l'accès Docker autorisé hors sandbox. Aucun échec expérimental des garanties n'a été corrigé par un changement de runtime ou un affaiblissement d'assertion. La passe canonique contient les diagnostics attendus des tests négatifs de migrations/contraintes, ainsi qu'un avertissement Surefire de fin de fork dans le module de persistance ; son code de sortie est 0 et tous ses rapports de tests sont verts.

Extraits d'observations réellement produites par la passe canonique (PID/XID propres à cette exécution) :

```text
Shared fence independent sessions: pid=71 xid=862 / pid=72 xid=863
Shared fence independent sessions: pid=86 xid=935 / pid=87 xid=936
Observed PostgreSQL wait: pid=60 blocked by pid=86
Observed PostgreSQL wait: pid=60 blocked by pid=87
Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
```

Le premier couple correspond à A. Le second couple et les deux attentes successives correspondent à B : le même detach attend encore la seconde transaction après la résolution de la première. Ces traces complètent les assertions d'état durable ; elles ne les remplacent pas.

Journaux locaux de cette session : `/private/tmp/pocoma-step2c-command.log`, `/private/tmp/pocoma-step2c-repeat.log` et `/private/tmp/pocoma-step2c-targeted.log`. Ils ne sont pas ajoutés au dépôt ; les tests recréent les observations et rapports Surefire à chaque exécution.

Contrôles Git : `git status --short`, `git branch --show-current`, `git rev-parse HEAD`, `git remote -v`, `git worktree list`, `git fetch origin`, `git rev-list --left-right --count HEAD...origin/feat/consumption-transaction-protocol`, inspection du diff puis `git diff --check`. Ajout explicite des deux seuls fichiers de cette étape, revue du diff indexé, commit et push normal vers la branche cible après nouvelle vérification distante. Le SHA final, le résultat effectif du push, la divergence et l'état du worktree sont annoncés dans la livraison après ces opérations, sans référence circulaire au hash du présent commit.

Aucun franchissement de slice ; aucun gate global requis ou exécuté ; aucun reactor complet exécuté. Aucun changement de production, schéma, migration ou dépendance. Le travail préexistant du checkout principal reste hors du commit.
