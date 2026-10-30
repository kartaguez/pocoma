# L3 / I2 — Autorité Identity PostgreSQL

État : I2 IMPLEMENTED — preuves locales satisfaites, gate canonique FAIL.
Aucun I2 LOCAL BOUNDED PASS ni GO I3.

## Référence et préalable

HEAD initial local/distant vérifié : `02c7bfbf85936f1f1a633e9097bc2b49f878708e`.
Aucun commit intervenu depuis. Checkout principal `feat/runtime-monolith` et
travaux étrangers conservés intégralement. Worktree utilisé :
`/private/tmp/pocoma-v3-postaud`, `feat/v3-architecture`.
Les documents préexistants du diagnostic I1 ont été lus, l'analyse des archives
rejouée avec succès (intégrité uniquement), les liens contrôlés. Clôture I1
publiée séparément : `a93a83d5cdbe6c043b5f6618d6b6297f40a59241`.
I1 ACCEPTED WITH EXPLICIT RESERVATIONS ; gate COMMAND + Architecture FAIL,
`i5_r05`/`i5_r12` conservés, aucun I1 LOCAL BOUNDED PASS.

## Verification scope — déclaré avant code

- Impact production attendu : `JdbcIdentityAuthority`, générateur SecureRandom,
  migration additive WRITE V6 ; réemploi des contrats et transitions I1.
- Slice primaire : COMMAND (`runtime-command-consumption-worker`).
- Slices secondaires : NONE.
- Preuve locale : les quatre classes I2 prévues, sur PostgreSQL réel ; histoire,
  absence/ancre zéro, concurrence avec barrières et observations serveur,
  rollback, contraintes sous rôle runtime, fermeture et catalogue des triggers.
- Gate Architecture globale : REQUIRED, nouvelles règles de writers.
- Full reactor : NOT REQUIRED ; ni clôture Wave majeure ni intégration main.
- DB : CURRENT V3 WRITE V1…V6 ; V6 disponible au HEAD réel, aucun V6 préexistant.
  Bootstrap V1…V5 historique des tests L1/L2 conservé. Aucune baseline V2 TRUSTED
  présumée, aucune campagne historique séparée.
- Interdit : I3 participant/store actif, I4 Pot, I5, runtime, READ, HTTP, migrations
  publiées, owner physique, mécanismes/seuils/tests temporels L2.
- Escalade : redéclaration avant tout changement d'owner/routage/contrat V2,
  consommateur supplémentaire ou frontière imprévue.

Commandes prévues depuis `app/` (leur exécution sera enregistrée séparément) :

```
./mvnw -pl infra-persistence-primary-jpa -am -Dtest=IdentityAuthorityPostgresTest,IdentityConcurrencyPostgresTest,IdentitySchemaPostgresTest,BindingTokenGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl runtime-command-consumption-worker,architecture-tests -am test
```

La première est un feedback local filtré, jamais un substitut à la seconde.
La seconde peut réexécuter les campagnes L2 transitivement, sans modification
ni exclusion. FAIL, SKIP et INCONCLUSIVE seront conservés.

## Implémentation candidate

Deux classes productives, une migration, quatre classes de tests PostgreSQL/token
et une garde d'architecture I2. Aucun POM à modifier : les dépendances nécessaires
existent déjà. Aucun fichier métier/owner/test temporel L1/L2 modifié.
L'autorité JDBC impose l'acquisition du jeu complet avant current/apply, utilise
le comparateur I1 et la garde SpringAttemptTransactions existante ; pas de
callback ni ressource transactionnelle propriétaire. Les protections Java sont
associées par référence faible au holder déjà possédé par l'owner ; les verrous
PostgreSQL restent détenus physiquement jusqu'à résolution.
Les erreurs JDBC sont réduites à leur SQLSTATE sans conserver DETAIL/cause SQL
pouvant révéler un token. Les événements et résultats n'en contiennent pas.

V6 contient les sept tables prévues, les huit FK immédiates nommées, PK/UNIQUE/
CHECK, gardes d'immuabilité, attribution writer_xid réelle, succession locale et
les dix triggers structurels/terminaux différés. Les compteurs IDENTITY et
IDENTITY_COMPLETION sont ajoutés sans altérer les autres données. Les gardes
additionnelles counter/position empêchent des allocations orphelines ou à trous ;
elles ne consultent que les structures de séquence dans le suffixe. Le premier
état des opérations livrées doit être lié (pas de v1 libre fictive). La phase
structurelle est forcée par ses deux noms, jamais SET CONSTRAINTS ALL.
Ce premier feedback n’avait pas identifié le défaut inter-familles décrit et
corrigé ci-dessous ; il ne certifiait donc pas toute la compatibilité du suffixe.

Feedback candidat avant gate : 25 tests I2 et 21 contrôles d'architecture ciblés,
46/0/0/0, PostgreSQL 17.11 aarch64, image épinglée par digest. C'est un feedback
filtré, aucune gate canonique n'en est déduite. Les premières itérations ont aussi
produit des FAIL de compilation/harness/oracles I2 ; leurs journaux sont conservés.
La gate non filtrée reste à exécuter ; son résultat peut empêcher tout verdict
technique canonique. L'arbitrage I1 ne la dispense pas.

## Finding interne I2 — suffixe des autres familles

La revue après gel `6ed1599a` a identifié un défaut I2 : le trigger
`v3_identity_position_unit` se déclenchait pour toute position, puis consultait
les tables Identity avant de reconnaître POT/COMMAND. Des SELECT simples peuvent
ainsi prendre de nouveaux verrous de relations métier dans un suffixe L2 qui ne
les a pas ouvertes. Le feedback Link seul ne démontrait pas leur absence pour
les autres familles. La première gate canonique a été arrêtée intentionnellement
(SIGTERM, exit 143), après 996 tests terminés sans échec : **INTERRUPTED**, pas
PASS ni échec imputé aux tests L2. Journal et SHA candidat conservés.

Correction minimale annoncée avant modification : condition WHEN sur les deux
familles Identity, conformément au §4.2.1 du plan. Aucun contrôle affaibli : les
triggers d'unité/enveloppe exigent toujours une position exacte de la bonne
famille, même si une position POT est tentée pour un événement Identity. Une sonde
PostgreSQL des suffixes POT/COMMAND doit démontrer zéro relation métier Identity
ouverte ; sa variante négative recrée le trigger non borné et doit être détectée.
Aucun changement de l'owner ou des writers/contraintes Pot/Command existants.
Ajout aussi d'un préflight de harness négatif sur une vraie DB SQL_ASCII ; il ne
livre aucun préflight runtime I3.

Feedback après correction : **27 tests I2 + 21 contrôles structurels ciblés,
48/0/0/0**. La sonde négative retrouve le défaut original et la version corrigée
passe ; la tentative de position POT pour une enveloppe Identity est rejetée au
commit et annule toute l'unité. Le premier feedback correctif comportait une
variante de harness POT encore mal sélectionnée (27 tests / 1 failure), corrigée
et conservée dans `suffix-correction-initial` ; aucun test L2 modifié.

## Commandes applicatives réellement exécutées

Depuis `app/`. Les reçus stockent la commande complète et les comptes tirés du
journal ; le collecteur ne recompte aucun XML résiduel non mentionné par ce journal.

```sh
# A — deux premiers feedbacks (compilation, puis autorité/générateur)
./mvnw -pl infra-persistence-primary-jpa -am -Dtest=IdentityAuthorityPostgresTest,BindingTokenGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false test
# B — trois feedbacks des quatre classes I2 avant formatage
./mvnw -pl infra-persistence-primary-jpa -am -Dtest=IdentityAuthorityPostgresTest,IdentityConcurrencyPostgresTest,IdentitySchemaPostgresTest,BindingTokenGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false test
# C — premier contrôle structurel ciblé
./mvnw -pl architecture-tests -am -Dtest=V3IdentityAuthorityArchitectureTest,V3ConsumptionEngineArchitectureTest,V3PotExecutionArchitectureTest,EventPublicationContractArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false test
# D — candidat formaté, correction initiale, correction finale
./mvnw -pl architecture-tests -am -Dtest=IdentityAuthorityPostgresTest,IdentityConcurrencyPostgresTest,IdentitySchemaPostgresTest,BindingTokenGeneratorTest,V3IdentityAuthorityArchitectureTest,V3ConsumptionEngineArchitectureTest,V3PotExecutionArchitectureTest,EventPublicationContractArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false test
# E — première tentative interrompue, puis nouvelle gate sur 49ae298d
./mvnw -pl runtime-command-consumption-worker,architecture-tests -am test
```

| Exécution | Commande | Résultat / portée |
|---|---|---|
| compile-initial | A | FAILURE compilation, zéro test ; erreur de typage d'assertion I2 |
| authority-initial | A | 8 / 1 failure / 0 error / 0 skip ; exception de version 0 correctement encapsulée par l'owner, attente du test à corriger |
| local-iteration-1 | B | 25 / 2 failures / 3 errors / 0 skip ; erreurs d'oracle/instrumentation I2 conservées |
| local-iteration-2 | B | 25/0/0/0 |
| local-before-format | B | 25/0/0/0 |
| architecture-before-format | C | 21/0/0/0 ; feedback uniquement |
| candidate-feedback, arbre identique à 6ed1599a | D | 46/0/0/0 ; avant découverte du trigger de position non borné |
| canonical-interrupted, 6ed1599a | E | 996 tests terminés, 0 failure/error/skip ; interruption volontaire, exit 143 ; gate non complétée |
| suffix-correction-initial | D | 27 / 1 failure / 0 error / 0 skip ; variante de harness POT mal sélectionnée |
| corrected-feedback, arbre identique à 49ae298d | D | 48/0/0/0 : 27 I2 + 21 architecture ciblés |

Les résultats intermédiaires restent des observations de leurs versions propres.
Aucun FAIL n'est effacé ni attribué au candidat final ; aucune répétition d'une
gate inchangée pour rechercher un succès. La relance E suit une correction SQL
matérielle, accompagnée de son contre-exemple déterministe.

Commandes d'outillage : contrôles Git (`status`, `worktree list`, `show-ref`,
`rev-parse`, `log`, `diff`, `ls-remote`, `add`, `commit`, `push`), lectures `rg`/
`cat`/`sed`, vérifications SHA-256/gzip/tar/XML et liens par Python ;
`analyze.py` I1 avant tout code ; `collect.py` I2 avec commandes/exit explicites ;
formatage des seuls sept nouveaux fichiers Java via google-java-format 1.24.0
`--aosp --replace`. Docker est uniquement utilisé pour les PostgreSQL jetables
de Testcontainers. Les commandes d'arrêt ciblent exclusivement les PID Maven/
Surefire de la première gate, jamais un service de l'utilisateur.

## Preuves PostgreSQL et limites

Les [allocations I2](../testing/evidence/L3-I2/Coverage.json) relient les 27
méthodes aux variantes et aux oracles indépendants ; le [catalogue DDL](../testing/evidence/L3-I2/DDL.json)
inventorie les objets. Les archives de chaque exécution contiennent journal,
XML correspondants et traces I2 : PID/XID, attentes `pg_blocking_pids`, ordre exact
INSERT/FOR UPDATE, résolution physique, catalogues/fonctions et photographies
SQL durables. Les valeurs sensibles sont omises ; les comparaisons de tokens
restent dans les assertions privées, les vecteurs avant/après exportent seulement
des empreintes de lignes. Les lignes publiques exportées incluent versions,
UserId, provenance et positions mais jamais token ou contexte d'admission brut.

Les succès de courses sont observés après commit depuis une connexion indépendante,
les perdants sont des rejets sans User supplémentaire. Les fautes de persistance
et collisions de tokens restent techniques et annulent l'unité entière. Les
barrières et arêtes de blocage serveur constituent les preuves d'entrelacement ;
aucun sleep n'est utilisé comme oracle.

Le harness I2 est explicitement contrôlé : il admet les demandes, protège et
supprime leurs slots puis finalise leurs enveloppes et positions avec toutes les
contraintes actives. Il ne livre pas l'admission externe, le participant I3,
le raccord Pot I4, un Worker ou des projections. Les sondes du suffixe des autres
familles prouvent seulement l'absence de régression du nouveau trigger sur ces
structures techniques ; elles ne certifient pas un nouveau parcours Pot/Command.
Le préflight testé est celui du harness/driver ; aucun runtime I3 n'est activé.

## Résolution de la gate finale et verdict

SHA testé : **`49ae298d879a41b29d1caf14e7597dffb947713a`** ; aucun changement
sous `app/` après ce gel. [Reçu canonique](../testing/evidence/L3-I2/canonical-final.json)
et archive correspondante : commande E sans filtre, exit **1**, **BUILD FAILURE**,
**45 min 27 s**, terminée le 30-10-2026 à 15:32:43 +01:00.

| Observation fraîche | Résultat exact |
|---|---|
| Gate composée COMMAND + Architecture | **1 822 tests, 1 failure, 0 error, 1 skip** |
| IdentityAuthorityPostgresTest | 6/0/0/0 |
| IdentityConcurrencyPostgresTest | 8/0/0/0 |
| IdentitySchemaPostgresTest | 11/0/0/0 |
| BindingTokenGeneratorTest | 2/0/0/0 |
| `i5_r05` L2 | PASS, 52,451 s ; son FAIL historique I1 reste conservé |
| `i5_r12` L2 | **FAIL**, 95,825 s, `runtime condition deadline ==> expected: <true> but was: <false>` |
| Architecture canonique | **SKIPPED**, arrêt Maven dans COMMAND ; gate globale **NON SATISFAITE** |
| Architecture ciblée après correction | 21/0/0/0 ; contribution structurelle, jamais substitution à la gate |
| Skip | `TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness` : `System property [step3b.measure] does not exist` |

La pile `i5_r12` mène à `V3WorkerPostgresTest.Runtime.close:553`, via
`I5TemporalV2Postgres.campaign:947`. Cette observation appartient à la campagne
L2 inchangée et à son périmètre de fermeture ; le même nom/message a déjà échoué
historiquement. **Aucune causalité I2 n'est établie**, et aucune non-régression
globale n'est déduite de ce constat. La cause physique détaillée de cette nouvelle
exécution n'est pas réauditée ni automatiquement assimilée au diagnostic I1.
Le journal, les XML et les traces L2 fraîches sont préservés séparément ; aucun
seuil, test, filtre de gate, durée physique ou mécanisme L2 modifié.
La gate n'est pas relancée à entrées identiques pour rechercher un succès.

**Verdict : I2 IMPLEMENTED — PREUVES LOCALES SATISFAITES ; GATE CANONIQUE FAIL,
AUCUN I2 LOCAL BOUNDED PASS PRONONCÉ.** Livraison pour audit indépendant,
**arrêt après I2 ; aucun GO I3**. I1 reste ACCEPTED WITH EXPLICIT RESERVATIONS,
avec ses deux échecs historiques et sa gate non satisfaite. Les acceptations et
réserves L1/L2 ne sont pas modifiées.

## Impact observé, écarts et livraison

- Slice déclarée/exécutée : **COMMAND**, secondaires **NONE** ; composition avec
  la gate Architecture requise. Franchissement inattendu de slice : **NONE**.
  Le défaut de périmètre du nouveau trigger a été corrigé dans PRIMARY avant
  publication, sans élargir l'owner, les consommateurs ou les writers existants.
- Gate globale : **REQUIRED / ATTEMPTED / NOT SATISFIED**, module Architecture
  sauté dans la commande canonique ; contribution ciblée explicitement distincte.
- Full reactor : **NOT REQUIRED / NOT RUN** ; étape bornée, pas clôture de Wave
  majeure, restructuration large ou intégration main.
- DB : PostgreSQL réel CURRENT WRITE V1…V6 pour les nouveaux tests ; anciens
  bootstraps V1…V5 conservés. Migrations historiques V2 jusqu'à V31 exécutées
  transitivement dans la gate non filtrée, aucune campagne historique séparée.
- Écarts de réalisation : aucune adaptation Maven nécessaire ; garde
  `V3IdentityAuthorityArchitectureTest` ajoutée dès I2 pour les writers ; garde
  counter/position complémentaire, préflight de harness négatif SQL_ASCII et
  sonde inter-familles ajoutés pour fermer la preuve physique. Aucun I3/I4/I5.
- Fichiers exacts : [Files.json](../testing/evidence/L3-I2/Files.json), distinguant
  la clôture I1 préexistante vérifiée des ajouts I2. Le reçu
  [Verification.json](../testing/evidence/L3-I2/Verification.json) relie les
  sources, commits, exécutions et archives ; aucune attribution d'un XML résiduel.
- Publication : push normal sur `feat/v3-architecture` après ultime contrôle
  distant, sans force. SHA documentaire final et confirmation distante donnés
  après création du commit pour éviter une auto-référence circulaire.
  Le checkout principal et tous les autres worktrees demeurent préservés.
