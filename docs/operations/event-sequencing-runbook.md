# Séquencement transactionnel — runbook 4E

État : procédure du candidat local pour répétitions isolées ; **aucune autorisation de production**.
Publication documentaire seule : le code, V31 et les outils exécutables cités restent locaux,
non committés. Ce runbook ne peut être exécuté depuis la seule révision documentaire.
Quatre familles seulement, quatre autorités E indépendantes, aucun ordre inter-familles.
[Rapport et limites](../architecture/Event_Sequencing_Step_4E_2026-10-20.md).

## Conditions d'entrée

L'opérateur désigne l'environnement, la version réellement déployée et les versions de remplacement.
Ne pas déduire ces informations de la branche. Vérifier que **tous** les producteurs utilisent
les owners 4D : Command TE/TR/TH, Registration, Business auxiliaire, Binding auxiliaire/lifecycle,
imports/scripts SQL. RegistrationOutcome ne constitue pas une famille. Identifier également
monolithe et instances en cours de redémarrage ; interdire leur redémarrage pendant le gel.

Fixer avec le responsable les seuils débit/p95/p99/backlog/arrêt, la durée maximale de maintenance,
le plan de retour avant ACTIVE et l'autorisation propre à cet environnement. Les résultats locaux
ne constituent pas un SLO ni une validation d'une version déployée non inventoriée. Version de
protocole 1, PostgreSQL 17 qualifié, isolation runtime **READ COMMITTED**, DDL V31 requis.
Les binaires admis en ACTIVE doivent inclure la correction 4E de perte d'accusé COMMIT.
La version SQL 1 seule ne certifie pas un binaire 4D ; l'outil ne fournit pas d'attestation
cryptographique de la flotte. Le retour au binaire 4D est qualifié uniquement en PREPARED,
sous arrêt/drainage, avant activation.

Séparer login runtime et propriétaire de migration/maintenance. Désactiver l'exécution Flyway
avec le login runtime et fournir les migrations dans la phase de maintenance. Le rôle runtime
n'est propriétaire ni de la base ni du schéma ni des tables/fonctions ; il ne peut hériter ou
prendre le rôle maintenance, modifier des paramètres désarmant les triggers, faire du DDL ou
tronquer les sources. Les schémas du search_path runtime sont de confiance et non inscriptibles.
Le groupe `pocoma_event_runtime` ne suffit pas à prouver le provisioning complet de l'application.
Les droits DML CURRENT des autres tables doivent être provisionnés séparément et éprouvés.

R1 reste explicite : la réservation technique et INSERT d'associations permettent à un acteur
SQL hors composition de casser la densité. Ne pas donner ces identifiants à des imports/outils
arbitraires. Toute nouvelle voie SQL exige requalification. Les contrôles ne suppriment pas R1.

## Préparation et gel

1. Obtenir une sauvegarde **restaurée et vérifiée** sur une cible séparée ; conserver son identifiant,
   point de restauration et procédure. Répéter le cutover sur une copie représentative : volume,
   UUID et facts V22 `MIGRATION_BASELINE` compris, y compris Events déjà consommés. La répétition
   locale générée de 4E ne remplace pas cette copie. Aucun replay de V22, reset de Claim ou payload.
2. Provisionner le login maintenance propriétaire des objets avec visibilité `pg_read_all_stats`.
   Il doit pouvoir verrouiller toutes les sources, modifier E/mode et exécuter les fonctions
   invoker. Le rôle runtime n'a aucun EXECUTE sur ces fonctions. V31 ne crée aucun login maintenance.
3. Configurer la connexion via PGSERVICE ou variables PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSFILE.
   Utiliser un `search_path` de maintenance vers le schéma d'installation, sans schéma inscriptible
   par runtime. Ne pas mettre un mot de passe ou URL de connexion dans les logs.
4. Exécuter `scripts/operations/event-sequencing.sh inspect LOGIN_RUNTIME` et archiver la sortie.
   PREPARED : des Events sans position sont attendus, leur présence n'est pas un défaut.
   `audit_event_sequencing_role` doit retourner **zéro finding** ; vérifier aussi les paramètres
   de login, pool, memberships, datasource PRIMARY unique, owners et versions de tous les processus.
   Pour plusieurs logins, inspecter chacun ; passer le groupe commun au cutover pour drainer tous
   ses membres. L’audit du groupe couvre aussi tous ses membres transitifs et leurs autres rôles,
   y compris les chemins SET ROLE/NOINHERIT. Vérifier qu'aucun login producteur non membre n'est oublié.
5. Arrêter admissions selon le budget de backlog, puis producteurs, workers Command/Registration,
   monolithe, auxiliaires, imports et toute maintenance concurrente. Attendre leur drainage confirmé.
   Relever `pg_stat_activity` et `pg_blocking_pids`. Maintenir le gel par le système de déploiement.
   L'API refuse une transaction encore active d'un membre du rôle fourni, même sans INSERT source.
   Elle ne sait pas reconnaître un processus externe utilisant un login non inventorié.

## Exécution explicite

Créer **une seule fois** un UUID d'opération et l'archiver avec environnement, opérateur, version,
sauvegarde, autorisation et résultats préalables. Réutiliser cet UUID lors de toute reprise.

```bash
POCOMA_CUTOVER_LOCK_TIMEOUT=5s POCOMA_CUTOVER_STATEMENT_TIMEOUT=5min \
  scripts/operations/event-sequencing.sh activate LOGIN_OU_GROUPE_RUNTIME UUID_OPERATION \
  > cutover.log 2>&1
```

Ces délais sont des limites techniques de l'outil, pas des seuils de production approuvés.
Les adapter au budget répété. La commande exécute BEGIN READ COMMITTED, verrouille mode/E,
quatre sources, quatre associations et journal en ordre fixe, puis vérifie rôles/contraintes,
draine les transactions, fait l'inventaire/backfill et valide avant de mettre ACTIVE dans
**le même commit**. Aucune famille ne s'active séparément. Les readers restent possibles sous
ces verrous s'ils ne font aucun DML bloquant ; les timeouts refusent une attente excessive.

Ordre historique : UUID texte canonique, collation C, positions 1..N par famille. Cet ordre est
arbitraire et reproductible, **sans reconstitution de causalité ou des anciens commits**.
Un préfixe déjà présent doit correspondre exactement à cet ordre et son compteur. Sinon STOP.
Pas de modification d'un couple existant, pas d'upsert/ON CONFLICT ignoré. Une variante de backfill
par lots committés n'est pas livrée : si la transaction ne tient pas le budget, garder PREPARED
et faire concevoir/approuver/qualifier une variante avec manifeste stable et reprise sous gel.

## Validation avant reprise

Sur une nouvelle connexion :

- Mode ACTIVE/version 1 et un journal à l'UUID prévu, login/groupe attendu et quatre frontières N.
- `inspect_event_sequencing()` retourne exactement quatre lignes `valid=true` :
  `count(source)=count(position)=E`, anti-jointures dans les deux sens nulles, positions uniques
  positives, min=1/max=E si non vide ; E=0 sinon. Les PK/uniques/FK sont actives.
- Exporter le manifeste exact `SELECT family,event_id,position FROM event_position ORDER BY family,position`
  dans le dossier d'évidence. Conserver les frontières N du journal ; elles restent historiques
  même lorsque de nouveaux Events portent E>N.
- Réexécuter l'audit des rôles et vérifier les quatre triggers de complétude différés actifs,
  les triggers d'immutabilité et les search_path SECURITY DEFINER. Ne jamais laisser une sonde
  d'intégrité committée comme Event métier arbitraire ; le refus d'ancien writer est prouvé sur
  la copie de répétition (INSERT sans position ⇒ 23514 au COMMIT).
- Démarrer seulement les versions homogènes compatibles ACTIVE, contrôler PRIMARY/manager/ports
  et mode avant admissions ; premiers vrais blocs après N, dans l'ordre de déclaration applicative.
  Vérifier les métriques et l'inventaire exact après ces premiers commits. Lever le gel suivant
  l'ordre approuvé et surveiller les seuils convenus.

## Interruption, UNKNOWN et reprise

Avant COMMIT confirmé, aucune exception client ne prouve à elle seule un rollback. Garder le gel.
Relire mode, journal et intégrité sur une connexion indépendante :

- PREPARED et absence de journal : une transaction annulée ne conserve ni nouveau couple ni avance E.
  Corriger la cause et relancer **le même UUID** sous gel ; le préfixe éventuel exact est vérifié.
- ACTIVE et journal correspondant : COMMIT effectif, même si le client a vu une erreur réseau.
  Relancer le même UUID ne renumérote rien et exige encore intégrité/rôles/drainage ; reprendre
  seulement après réconciliation. Un autre UUID est refusé.
- Journal/mode/données contradictoires, opération inconnue ou intégrité fausse : **STOP**, conserver
  le gel et l'évidence, investigation maintenance ; aucune réparation automatique.

Ne pas rejouer aveuglément un Command/Registration UNKNOWN. Réconcilier outcome, S/Claim et UUID
connus sur nouvelle connexion, selon Consumption existant. COMMITTED, ROLLED_BACK et UNKNOWN
restent distincts ; aucun FAILED automatique sur UNKNOWN, aucun retry local de E.

Après le premier COMMIT ACTIVE : positions immuables, correction en avant. Arrêter les producteurs
si nécessaire ; ne pas désactiver les triggers, remettre PREPARED, renuméroter ou revenir à un
ancien runtime automatiquement. Une restauration perdant des commits est une décision d'incident
explicite avec analyse des données à préserver et nouveau protocole de reprise.

## Diagnostic et arrêt de sécurité

`docs/operations/sql/event-sequencing-locks.sql` fournit mode/E et transactions bloquantes sans
scan historique. Utiliser un compte monitoring à visibilité adéquate. Relever wait_event,
`pg_blocking_pids`, âge de transaction, requête, timeouts/SQLSTATE, deadlocks et métriques DB/WAL.
Conserver les deltas de `pg_stat_database` et `pg_stat_wal` avec les horodatages.

Métriques Micrometer, sans identifiants événement/transaction en tags :

- `pocoma.event.sequencing.suffix{result=success|failure}` : durée du suffixe (PREPARED inclus).
- `pocoma.event.sequencing.spring.resolutions{outcome=committed|rolled_back|unknown}` : notification Spring des
  transactions parvenues au suffixe ; les erreurs d'action antérieures relèvent de Consumption.
  Cette notification n'est pas l'autorité de résolution durable : Hibernate peut notifier rollback
  après perte d'accusé COMMIT ; le marqueur `TransactionCommitUnknownException` et le wrapper
  Consumption conservent alors UNKNOWN. Corréler erreurs de transport et réconciliation DB.
- `pocoma.event.sequencing.suffix.to.resolution` : fenêtre entrée suffixe→résolution, **borne haute**
  du temps sous E ; contient lecture du mode et attente d'acquisition.
- `pocoma.event.sequencing.commit.tail` : fin suffixe→résolution, **borne basse** du temps sous E.
  Ce ne sont pas deux mesures exactes d'acquisition E→commit.

Mode/E se lisent à faible coût ; Events sans position et écarts de densité exigent le scan exhaustif
`inspect_event_sequencing`, sur demande ou avec fréquence/budget approuvés par l'exploitant.
Ne pas lancer ce scan par transaction. Le relevé peut nécessiter REPEATABLE READ pour un instantané
cohérent ; le script inspect le fournit. Les métriques de charge locales mesurent en plus les
horodatages serveur après UPDATE E et les vrais commit timestamps, sans modifier les compteurs.

Anomalie de densité, timeouts répétés, blockers anormaux ou dépassement des seuils : geler
producteurs/admissions selon budget, capturer les diagnostics et l'inventaire dans un instantané,
préserver la DB. Ne pas « combler » une position, avancer E, supprimer un Event ou désarmer une
contrainte. Identifier d'abord si l'hypothèse R1/provisioning/voie SQL a été violée. Toute réparation
requiert un protocole dédié, explicitement conçu, autorisé et certifié.

Pour un appel autonome Business auxiliaire, le UUID est rendu après résolution ; une perte d'accusé
peut donc empêcher le client de le recevoir. Utiliser la corrélation métier/technique conservée
par l'appelant pour investiguer. En l'absence de corrélation univoque, maintenir UNKNOWN et le gel,
sans replay automatique. Les preuves de transport 4E connaissent les UUID acceptés dans un owner
englobant ; elles ne livrent pas une nouvelle clé d'idempotence externe à cette surface auxiliaire.
