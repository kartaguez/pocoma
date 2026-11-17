# Pocoma V3 — L4/I1 — Exécution et qualification du stockage immuable READ

Date : 2 novembre 2026. Branche : `feat/v3-architecture`.
GO humain : mandat explicite de cette mission ; il supplante les mentions
historiques « aucun GO » des deux plans de référence. Aucun nouveau cadrage ni
audit préalable n’a été lancé.

**Verdict officiel courant : CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT
RESERVATIONS**, décision humaine définitive du 2 novembre 2026, sur le dossier
`3bba9dbaa4fedfe040ed9f7e5d7ca2239b1ddab7`.
L’[acte de clôture dédié](L4_I1_Acceptance_Decision.md) distingue implémentation,
qualification locale, recommandation d’audit et décision humaine. **I2 peut
être engagé après son propre cadrage technique** ; L4 reste ouvert.

**Verdict d’implémentation historique : IMPLEMENTED — LOCAL BOUNDED PASS.**
Les obligations techniques I1 sont démontrées dans le périmètre décrit par les
campagnes publiées. À la livraison initiale, l’audit indépendant du code et des
preuves, puis la clôture humaine, restaient à venir. Les résultats et récits
ci-dessous conservent leur attribution historique ; cet ajout ne produit ni
audit ni nouveau rejeu Maven/PostgreSQL.

Réserves conservées par la décision : preuves PostgreSQL issues des campagnes
archivées, sans nouveau rejeu physique par l’audit indépendant ; FAIL Architecture
initial et diagnostic des bytecodes WEB suspects conservés avec la qualification
ultérieure isolée ; bornes mémoire du codec et volumes réels à examiner aux
incréments métier, sans défaut I1 établi ; correctif de poisoning tardif L2 à
préserver et inclure dans l’analyse de régression I2 avec PreparationReads.
Ces réserves ne bloquent pas artificiellement la poursuite et n’étendent aucune
certification. I2, matérialisations I3–I7, activation I8, qualification globale L4
et contrats futurs L5/L6 restent hors clôture I1.

## Frontière déclarée et réalisation

- SHA initial : `4028f2fa9f053e75dd60bb0ccefd091014a4fb9d` ; worktree initial propre.
- Slice initiale : **PROJECTION**, aucune secondaire. Après contre-preuve du
  poisoning tardif JDBC : **PROJECTION + COMMAND**, compositions V3 du monolithe
  dans la gate Architecture ; élargissement déclaré avant correction de l’owner.
- Architecture globale : **REQUIRED**, adaptations explicites des dépendances
  pures et de la règle du port. Commande non filtrée obligatoire.
- Full reactor : **NOT REQUIRED / NOT RUN** ; I1 ne clôt ni L4 ni une intégration
  globale et ne restructure pas les modules/runtimes.
- Base : **CURRENT V3**, installation neuve et extension d’une L2 peuplée ; aucune
  baseline historique TRUSTED invoquée. Les migrations historiques entraînées
  transitivement par les gates restent attribuées à ces gates.
- Franchissement inattendu : **infra-tx-spring / owner L2**, strictement
  indispensable et résolu par le correctif décrit ci-dessous. Engine, slots,
  Discovery, préparation/recovery et compositions actives restent inchangés.

L’implémentation suit les signatures et emplacements du
[contrat I1](L4_I1_Implementation_Plan.md). D1–D4 sont préservées. Les huit types
sont stockables sans activer de matérialisation métier. I2, I3–I8, HTTP, L5/L6 et
la rationalisation des runtimes restent hors périmètre.

### Fichiers applicatifs et contrats

L’inventaire exact et les empreintes figurent dans le [manifeste](L4_I1_Proof_Manifest.json) et l’[inventaire applicatif](../testing/evidence/L4-I1/Implementation_Files.json).

| Module / fichiers | Contrat livré |
|---|---|
| `domain-projection/pom.xml`, `v3/SubobjectKind.java`, `SubobjectKey.java`, `SubobjectContent.java`, `SubobjectVersion.java` | Huit wrappers sealed des records Pot existants ; clé `(pot_id,kind,object_id,local_version)` ; identité Pot/objet/type ; versions positives ; égalité complète ; ordre UUID non signé et rang explicite des kinds |
| `port-projection/pom.xml`, `v3/SubobjectVersionStore.java`, `SubobjectConflictException.java`, `CorruptSubobjectException.java` | Port étroit `readAll`, `insertOrVerifyAll`, `requireStored` ; contexte de snapshot READ explicite ; diagnostics sans payload |
| `infra-persistence-read-jdbc/pom.xml`, `v3/SubobjectJson.java` | Codec schema 1 explicite ; champs exacts, propriétés dupliquées, types/nullité, UUID/date canoniques, fractions BigInteger irréductibles, participants ordonnés sans doublon ; aucune normalisation d’une forme persistée corrompue |
| `infra-persistence-read-jdbc/.../v3/JdbcSubobjectVersionStore.java` | Garde L2 couvrant validation/codec/SQL ; source identique à l’owner ; aucun begin/commit/rollback autonome ; batch trié, chunks de 256 au maximum ; INSERT ON CONFLICT DO NOTHING suivi d’un SELECT distinct à READ_COMMITTED ; relecture effective de readiness ; poisoning même après interception |
| `db/v3-read-business/migration/V2__subobject_version.sql` | Extension additive READ, même historique Flyway V3 ; PK/checks/JSONB ; triggers UPDATE/DELETE/TRUNCATE ALWAYS ; writer SELECT/INSERT seulement ; rôles/cibles/fonctions auxiliaires contrôlés |
| `infra-tx-spring/.../v3/SpringAttemptTransactions.java`, `AttemptTransactionsPostgresTest.java` | Correction minimale du poisoning JDBC tardif, avec régression PostgreSQL partagée ; détail et frontière révisée ci-dessous |
| `architecture-tests/.../HexagonalArchitectureTest.java` | Exception limitée au contexte `RecoveryReadContext` depuis le nouveau port v3 ; exception pure Pot limitée au domaine projection v3 ; contrôles négatifs maintenant les anciennes interdictions |
| `domain-projection/.../SubobjectVersionTest.java`, `infra-persistence-read-jdbc/.../SubobjectJsonTest.java` | Valeurs, contenu complet, collections détachées, ordre et corruptions de représentation |
| `runtime-task-consumption-worker/.../v3/{SubobjectPostgresFixture,SubobjectStorePostgresTest,SubobjectMigrationPostgresTest,SubobjectAtomicityPostgresTest,CommitAcknowledgementProxy}.java` | Banc test-only avec Engine/claims/owner L2 réels, rôles ordinaires, sessions indépendantes, barrières SQL, témoins transactionnels et perte d’ACK filaire |

L’owner reçoit la correction minimale de poisoning tardif justifiée ci-dessous.
Les autres mécanismes L2 sont réutilisés sans changement. Le participant
technique n’est présent que dans les tests ; aucune préparation WRITE ni branche
cachée I2 n’a été introduite. Le store n’efface pas le slot et ne ferme pas la
publication : `Prepared.requireCompletionReady()` relit le préfixe, puis L2
supprime le slot, puis le participant ferme la publication.

### Migration

Installation neuve : Flyway applique **V1 et V2 dans une même invocation**, avec
les deux localisations et `flyway_schema_history`, dans un schéma non-public
possédé par un admin distinct du runtime. Les scripts L2 existants installent
ensuite le routage et les droits lifecycle.

Extension : V1 seule et scripts L2 ; routes, ownership, checkpoint et slot
réellement acquis sont peuplés avant V2. Les lignes complètes, définitions,
contraintes, index, ACL de table/colonnes, fonctions et ligne d’historique V1 sont
comparés avant/après. V2 s’enregistre une fois ; le rejeu sans pending applique
zéro migration. Le slot conservé est ensuite finalisé via l’Engine réel.
Les catalogues métier neuf/extension sont comparés indépendamment.

Les connexions runtime tentent réellement UPDATE identique/divergent,
DELETE/TRUNCATE : SQLSTATE `42501`. L’admin rencontre les triggers : `23514`.
La PK exacte, l’unique index btree, l’absence de FK cross-authority, les deux
triggers ALWAYS, la fonction invoker et son search_path, l’absence de memberships
et les privilèges effectifs sont vérifiés. Un lecteur SELECT-only est une fixture
explicite. Une fonction auxiliaire SECURITY DEFINER accessible au runtime, y compris hors
du schéma READ, fait
refuser V2. Les mauvaises cibles et memberships dangereux sont refusés.
Une L2 peuplée sans historique valide refuse le bootstrap Flyway et le SQL V1
lui-même ; aucun reset, baselineOnMigrate, repair ou changement de checksum.

## Tests et preuves produites

Campagne ciblée finale : **37 tests / 0 failure / 0 error / 0 skip — PASS**,
invocation `bc033ccb299f41e2aec0411aacf208b7`, durée Maven 20,794 s.
L’inventaire source indépendant contient exactement ces 37 identités ; le
collecteur constate une exécution fraîche unique de chacune, sans problème de
découverte ni changement d’entrée. Les classes nouvelles comportent plusieurs
variantes contrôlées dans chaque méthode ; le total de méthodes n’est pas un
total de variantes. **60 fichiers physiques** sont archivés dans le brut ciblé.

| Classe | Méthodes | Contributions discriminantes |
|---|---:|---|
| `SubobjectVersionTest` | 4 | Huit kinds et sealed types, identités/version locale, chaque champ de contenu, participants détachés et doublons, frontières UUID 7fff/8000 et bits bas |
| `SubobjectJsonTest` | 4 | Huit encodages ; chaînes exactes, date étendue, grands rationnels, ordre des propriétés ; champs absents/inconnus, doublons JSON, faux types, UUID/date/fractions/participants corrompus |
| `SubobjectStorePostgresTest` | 11 | Huit contenus initiaux/répétés et divergences champ par champ ; conflit concurrent identique/divergent, rollback du gagnant ; attente réelle sur l’index unique ; SELECT séparé du perdant ; batches inversés de 600, dernier chunk divergent, absences, duplications/nulls ; corruption administrée ; owner/source/manager/contexte expiré et autre thread |
| `SubobjectMigrationPostgresTest` | 6 | Neuf/extension peuplée, préservation L2 et V1, rejeu sans pending, catalogue, droits/immutabilité réels, formes SQL invalides, mauvaises cibles, memberships et fonctions auxiliaires dangereux, refus de V1 peuplée sans historique |
| `SubobjectAtomicityPostgresTest` | 12 | Engine réel ; snapshots avant/après, verrou jusqu’à résolution ; toutes coupures, premier chunk et phase de commit ; readiness manquante/divergente ; erreurs interceptées ; fermeture précoce/absente/tardive, complétion double/absente/étrangère/expirée ; FK différée ; deux slots concurrents ; deux contre-oracles ; quatre pertes d’ACK ; refus des callbacks tardifs `beforeCommit`/`beforeCompletion`, propagés ou interceptés |

### Observations PostgreSQL nominatives

Environnement de la contribution : PostgreSQL **17.11**, image épinglée
`postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73`,
base jetable `i1_read`, schémas et rôles dédiés par scénario. L’admin de schéma
et le runtime sont ordinaires et distincts. Seul l’observateur diagnostique
indépendant utilise le compte conteneur pour inspecter les attentes d’autres
rôles dans `pg_stat_activity` ; aucun privilège de diagnostic n’est accordé au
runtime. JVM Temurin 21, Maven 3.9.14, Docker 29.9.2 ; provenance détaillée archivée.

| Scénario dans le brut ciblé | Observation |
|---|---|
| [effectsAndSlotSameUnit-i1_7560a08ca03f48e4b9d23cb75fe80483.txt](../testing/evidence/L4-I1/physical/effectsAndSlotSameUnit-i1_7560a08ca03f48e4b9d23cb75fe80483.txt) | Owner **PID 1564 / XID 1519** ; huit INSERT et un DELETE avec les mêmes PID/XID/base/schéma/isolation `read committed`. Une session extérieure constate `(effets,slot,témoins)=(0,1,0)` avant commit puis `(8,0,9)` après, par une instruction cohérente à chaque fois ; le concurrent reçoit `55P03` pendant la protection |
| [counterOracle-independent-false-i1_5c71622e1625485b8c802953f0fb4e05.txt](../testing/evidence/L4-I1/physical/counterOracle-independent-false-i1_5c71622e1625485b8c802953f0fb4e05.txt) | Deux unités committées paraissent correctes en état final, mais INSERT **PID 1525 / XID 1498** et DELETE **PID 1524 / XID 1497** : oracle rejeté |
| [counterOracle-independent-true-i1_b0cc4bdccccf47d2965a6702557e33eb.txt](../testing/evidence/L4-I1/physical/counterOracle-independent-true-i1_b0cc4bdccccf47d2965a6702557e33eb.txt) | Effet indépendant survivant au rollback de l’owner, slot conservé : oracle rejeté |
| [rollbackBeforeCommit-i1_eda9ab96969c4a82aebf5ccedc5f693a.txt](../testing/evidence/L4-I1/physical/rollbackBeforeCommit-i1_eda9ab96969c4a82aebf5ccedc5f693a.txt) | Faute dans `beforeCommit` après DELETE : aucune écriture/témoin durable, slot restauré ; L2 conserve historiquement **UNKNOWN / NOT_COMPLETED**, capacité retenue |
| `ack-BEFORE_COMMIT-unavailable-{false,true}-…` | COMMIT intercepté avant envoi : zéro réponse serveur COMMIT, zéro effet durable, slot présent ; **UNKNOWN**, observation NOT_COMPLETED ou UNAVAILABLE, aucune cession et capacité retenue |
| `ack-AFTER_COMMIT-unavailable-{false,true}-…` | Réponse COMMIT et ReadyForQuery du serveur consommées sans livraison JDBC, TCP rompu : huit effets et DELETE indivisibles ; **UNKNOWN conservé**, observation COMPLETED ou UNAVAILABLE ; aucun succès physique supposé ni capacité libérée |

Les traces de faute capturent le PID/XID dans l’owner avant rollback ; les témoins
transactionnels disparaissent avec les effets. Les lectures extérieures et la
comparaison du contenu complet complètent l’oracle physique. Ces preuves ne sont
pas obtenues par une transaction de démonstration indépendante de L2.
L’[index physique](../testing/evidence/L4-I1/Physical_Index.json) et l’[index des membres du brut](../testing/evidence/L4-I1/targeted/Archive_Members.json) donnent les chemins et empreintes
exacts ; aucun certificat PRJ-01/CON-06 global n’est revendiqué.

## Commandes et gates

Toutes les commandes Maven s’exécutent depuis `app/`. Le collecteur existant
`verify.py` est utilisé comme bibliothèque par l’opérateur archivé ; aucune
recette READ, aucun module et aucun outillage général n’a été ajouté. Il conserve
le modèle effectif, les identités attendues, les empreintes app/SQL/config, les
versions, les XML et les journaux, avec un UUID de producteur propre à chaque
invocation. Les anciens `target/*-reports` ne contribuent jamais au verdict.
Les deux propriétés `pocoma.verification.id` et `pocoma.verification.reports`
figurent dans les commandes exactes archivées ; elles isolent les preuves.

```sh
# Feedback initial valeurs/codec
./mvnw -pl infra-persistence-read-jdbc -am -Dtest=SubobjectVersionTest,SubobjectJsonTest -Dsurefire.failIfNoSpecifiedTests=false test
# Feedback store/migration, puis trois classes PostgreSQL
./mvnw -pl runtime-task-consumption-worker -am -Dtest=SubobjectStorePostgresTest,SubobjectMigrationPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl runtime-task-consumption-worker -am -Dtest=SubobjectStorePostgresTest,SubobjectMigrationPostgresTest,SubobjectAtomicityPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
# Recompilation propre après diagnostic incrémental
./mvnw -pl runtime-task-consumption-worker -am clean -Dtest=SubobjectVersionTest,SubobjectJsonTest,SubobjectStorePostgresTest,SubobjectMigrationPostgresTest,SubobjectAtomicityPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
# Feedback atomicité après renforcement des oracles
./mvnw -pl runtime-task-consumption-worker -am -Dtest=SubobjectAtomicityPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
# Campagne finale avec provenance : mêmes cinq classes, noms pleinement qualifiés
# Gates non filtrées, avec propriétés explicites de provenance dans les reçus
./mvnw -pl runtime-task-consumption-worker -am test
# Après franchissement indispensable vers l’owner partagé : slices composées
./mvnw -pl runtime-task-consumption-worker,runtime-command-consumption-worker -am -Dstep3b.measure=true test
./mvnw -pl architecture-tests -am -Dstep3b.measure=true test
```

Le paramètre des gates `step3b.measure=true` active effectivement le test
exploratoire historique conditionnel, pour exécuter tout l’inventaire sans SKIP.
Ce n’est ni un filtre ni une exclusion ni une modification d’assertion ; ses
mesures ne constituent aucune certification de performance I1. La réserve SKIP
acceptée pour L3/I5 n’est pas transposée à I1.

| Campagne | Résultat strict | Compteurs |
|---|---|---|
| Ciblée I1 finale | PASS | 37/0/0/0 |
| FOUNDATION après correctif owner (feedback filtré existant) | PASS | 29/0/0/0 |
| PROJECTION avant correction owner (développement seulement) | PASS attribué à ses anciennes entrées | 1511/0/0/0 |
| PROJECTION + COMMAND canoniques après correction owner | **PASS** | **1833/0/0/0** |
| Architecture sur sorties incrémentales suspectes | **FAIL**, module Architecture NOT RUN | **1500/0/2/0** |
| Architecture canonique, checkout isolé compilé frais | **PASS** | **2096/0/0/0**, dont **164/0/0/0** dans le module Architecture |
| Full reactor | NOT REQUIRED / NOT RUN | — |

La première collecte PROJECTION (`1153184c68804cda8ff0117ff7de1f8c`) a été
interrompue proprement pour corriger la garde des fonctions auxiliaires hors du
schéma READ. Son reçu **FAIL / interrupted**, ses XML partiels et ses journaux
sont conservés dans `projection-interrupted.tar.gz`. Les compteurs zéro de ce
reçu d’interruption ne signifient pas zéro test exécuté ; aucun inventaire
complet n’a été évalué. Elle ne contribue à aucun PASS. La campagne ciblée
antérieure, PASS sur ses anciennes entrées, reste attribuée au développement.
La campagne ciblée finale et les gates sont reparties dans de nouveaux
répertoires après la correction SQL et le test négatif interschéma.
Les témoins nomment désormais les quatre éléments de clé de chaque INSERT et
vérifient l’inventaire exact des clés ; les rollbacks comparent aussi la ligne
complète du slot acquis.

Les campagnes finales ont toutes un inventaire complet, une exécution fraîche
unique par identité, zéro failure/error/skip et aucun changement d’entrée :

| Campagne | Invocation | Durée Maven |
|---|---|---:|
| I1 ciblée | `bc033ccb299f41e2aec0411aacf208b7` | 20,794 s |
| FOUNDATION | `e3ea8ce8a578468f804eb93ca3301c58` | 11,363 s |
| PROJECTION + COMMAND | `d8eade5a0ea24492b2fbbdcce0efc6b1` | 3060,576 s |
| Architecture isolée | `5779c9b92c5b45ae877ff1367625b7ea` | 3237,731 s |

`i5_r05` et `i5_r12` sont PASS dans les deux gates finales, sans exclusion.
Le test exploratoire conditionnel a réellement été exécuté (15,796 s puis
15,907 s) ; ses mesures ne certifient aucun SLO. Les réserves historiques
restent inchangées. Les deux tests WEB initialement en erreur sont PASS dans
le checkout frais : respectivement 1 et 2 méthodes, zéro failure/error/skip.
Les [reçus et archives](../testing/evidence/L4-I1/README.md) conservent les
commandes complètes, configurations, inventaires et contrôles d’intégrité.

Les essais de développement et leurs échecs sont conservés : imports de valeurs
V2/V3 ambigus corrigés ; observateur de lock sans visibilité sur l’autre rôle et
trigger de témoin incorrect corrigés ; assertion générique produisant un
`Unresolved compilation problems`, puis recompilation clean ; oracles de
readiness et de phase de commit alignés sur les causes et résolutions L2 réelles.
Les PASS finaux n’effacent aucun de ces essais. Aucun échec inchangé n’est relancé
pour fabriquer un succès, aucun timeout productif n’est augmenté.

## Écarts, réserves et livraison

### Qualification isolée après diagnostic du bytecode WEB

La première exécution effective Architecture (`134342814b8d4cf6a4cf438a0f189a0f`)
termine **FAIL : 1500/0/2/0**, avant le module Architecture. Deux tests WEB
échouent au démarrage avec `Container POSTGRES needs to be initialized`.
Le désassemblage conservé dans
[`web-bytecode-diagnostic`](../testing/evidence/L4-I1/web-bytecode-diagnostic/Inputs.json)
montre des constructeurs remplacés par `throw new Error("Unresolved compilation
problems")`, alors que les sources et la configuration référencées existent.
Ce bytecode erroné ne constitue pas une compilation valide des sources Maven.
Le FAIL complet reste conservé ; aucune assertion ni source WEB n’est modifiée.

Selon la politique Reactor §35 sur les sorties périmées/suspectes, le rejeu se
fait dans `/private/tmp/pocoma-l4-i1-clean-qualification`, clone local temporaire
isolé, détaché au SHA initial, avec **copie et vérification SHA-256 des 1282 entrées
applicatives exactes**. Aucun `target/` suspect n’est copié. Maven y compile
fraîchement la fermeture Architecture ; mêmes commandes, image/versions,
propriétés de test et sources, nouvelle provenance. Le dépôt principal et les
autres worktrees sont préservés. Cette qualification n’est pas un full reactor.
Le [FAIL natif](../testing/evidence/L4-I1/architecture-stale-bytecode-failure/result.json)
et les classes `.class.bin`/désassemblages restent accessibles pour audit.


### Correction L2 strictement indispensable à I1

Une reproduction H2 a d’abord montré que Spring vérifie les marqueurs rollback
avant ses synchronisations, tandis que le commit JDBC ne lit pas le marqueur du
`ConnectionHolder`. La [contre-preuve PostgreSQL](../testing/evidence/L4-I1/owner-defect-before-fix/result.json)
a ensuite exécuté le store et le vrai Engine/slot L2 : erreur du store interceptée
après fermeture dans `beforeCommit`, puis **8 effets durables, slot absent et
9 témoins**. L’owner conservait UNKNOWN, mais le préfixe empoisonné avait committé.
Le test échoue réellement **1/1/0/0** ; son brut et sa trace restent archivés.

Le correctif dans `SpringAttemptTransactions` ajoute un
`TransactionExecutionListener.beforeCommit` stateless, enregistré une seule fois
par manager. Il s’exécute **après `beforeCommit` et `beforeCompletion` des
synchronisations, avant `doCommit`**. Il refuse le commit si l’Unit correspondant
exactement au `TransactionStatus` est empoisonné/rollback-only. Les transactions
sans Unit Attempt ne sont pas affectées. Aucune connexion/transaction autonome,
aucun nouveau protocole de claims, de préparation ou de recovery n’est introduit.

Le test I1 réel couvre désormais les quatre variantes propagées/interceptées ×
les deux phases, avec état complet du slot et témoins physiques. Un test partagé
supplémentaire dans `AttemptTransactionsPostgresTest` couvre l’owner JDBC READ.
La recette FOUNDATION existante donne **29/0/0/0 PASS**. Le diagnostic, le
franchissement et les consommateurs ont été déclarés avant la modification
productive ; les slices sont étendues à COMMAND et PROJECTION et les compositions
monolithiques restent dues dans Architecture. Le full reactor n’est pas requis :
le garde ne s’applique qu’aux Units V3 identifiées, les transactions historiques
sans Unit gardent leur comportement, et les consommateurs affectés sont bornés.

Le premier PROJECTION **1511/0/0/0 PASS** précède ce correctif : conservé sous
`development-projection-before-owner-fix`, il ne qualifie pas l’owner corrigé.
Les gates finales repartent sur toutes les entrées nouvelles.

### Attribution du collecteur Architecture

Le premier préflight Architecture est **INCONCLUSIVE / tests NOT RUN** : le
collecteur ne rattache pas `CurrentTransactionManagerPostgresTest`, seconde classe
top-level du fichier `JpaTerminalSequencingPostgresTest.java`, à sa source pour
le parcours des helpers/SQL. Le modèle effectif avait déjà découvert ses méthodes.
L’opérateur archivé ajoute **cette seule attribution explicite**, en vérifiant la
déclaration dans les octets source, puis utilise le même collecteur sans modifier
son code, la découverte des producteurs, les identités attendues, les filtres ou
les oracles. Le fichier réel entier et sa fermeture SQL sont ainsi tracés et
hachés. L’exception d’attribution figure dans le plan de chaque gate finale ;
le refus initial reste conservé, sans PASS ni test inventé.


L’adaptation de la garde d’architecture du domaine projection est nécessaire à la
dépendance pure `domain-projection → domain-pot` déjà autorisée ; le plan nommait
explicitement la garde de port et ne détaillait pas cette deuxième règle. Son
exception reste limitée au package v3 et aux valeurs Pot pures ; les contrôles
négatifs et la gate globale sont conservés. Cette adaptation de règle reste dans la frontière initiale ; le franchissement
vers l’owner partagé est traité séparément ci-dessus.

La résolution UNKNOWN d’une faute levée dans la phase Spring `beforeCommit`
est conservée telle quelle ; l’observation ultérieure du rollback ne la
réécrit pas. Ce comportement conservateur reste inchangé par le correctif de poisoning
tardif : le rollback observé ne réécrit pas l’ACK historique.

- État historique de la livraison initiale : aucun audit indépendant du code
  livré ni clôture humaine définitive I1 n’était alors revendiqué. Le statut
  courant relève désormais de l’[acte humain de clôture](L4_I1_Acceptance_Decision.md),
  sans réécriture des résultats de campagne.
- Pas de participant métier activé, pas de source WRITE@V, pas de préparation ou
  recovery interautorités I2, pas de certificat global READ/PRJ/SEC/OPS/PERF.
- Les réserves historiques L2/L3 ne sont ni effacées ni levées par ces preuves.
- SHA final applicatif : `533c14746b61395691845edf19f40e5444f9edd9`.
- Correctif owner L2 : `9e8c18a9830fa151808f6aef8a96ae978a199c87`.
- SHA exécuté : HEAD initial `4028f2fa…` avec worktree dirty et empreintes
  complètes ; le manifeste vérifie les **1282 blobs applicatifs** du commit
  final contre les octets réellement testés. Aucune exécution propre au SHA
  final n’est inventée.
- Commit documentaire de livraison : `git log -1 --format=%H -- docs/v3/L4_I1_Proof_Manifest.json`.
  Son SHA n’est pas inscrit de façon autoréférente dans ses propres octets ; le
  reçu final de publication fournit la tête exacte.
- Publication : branche `feat/v3-architecture`, push normal après commit du
  dossier. Le reçu final de livraison fournit la tête distante effectivement
  contrôlée et l’état du worktree ; aucun force push.


Contrôle de livraison : les espaces terminaux des tableaux `psql` dans les
transcriptions physiques sont conservés byte à byte et vérifiés par SHA-256.
Le contrôle de whitespace porte sur les sources, documents et métadonnées hors
ces transcriptions natives et le journal Maven brut :
`git diff --cached --check -- . ":(exclude)docs/testing/evidence/L4-I1/physical/**" ":(exclude)docs/testing/evidence/L4-I1/architecture-preflight-refusal/discovery.log.txt"`. Les sources n’ont aucun
écart de whitespace ; aucune preuve brute n’est nettoyée pour masquer ce format.
