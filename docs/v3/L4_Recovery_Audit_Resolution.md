# L4/I3–I7 — instruction ciblée des preuves de recovery

## Frontière déclarée avant correction

Départ : `b3b7b8c4f0ebc7c1bfb946c614359a4a8ea787bf`, branche `feat/v3-architecture` propre.
PROJECTION principale ; BINDING secondaire (preuve CurrentBinding), sans changement de son
runtime. Impact prévu : oracle PotVersion/CurrentPot et contre-tests des trois participants.
Interdits : moteur L2, owners, frontières transactionnelles, producteurs WRITE, identifiants,
familles, dépendances entre projections et activation productive. Aucun changement de module
ou de dépendance ; gate Architecture supplémentaire NOT REQUIRED, full reactor NOT REQUIRED.
Les gates historiques G-I3…G-I7/Architecture restent reportées à I8.

Ancrage canonique : `./mvnw -pl runtime-task-consumption-worker -am test` (PROJECTION),
`./mvnw -pl runtime-binding-consumption-worker -am test` (BINDING). Ces gates non filtrées ne
sont pas exécutées : mandat explicite de qualification strictement ciblée. Sélection effective
sur le premier ancrage, qui contient la composition réelle des trois participants, sans runtime
BINDING modifié. PostgreSQL CURRENT V3 via fixtures existantes, sans nouvelle migration,
sans chaîne historique V2 ni baseline TRUSTED inventée. Escalade : besoin de changement L2,
de transaction ou de contrat majeur => arbitrage, aucune extension silencieuse.

## Instruction contradictoire préalable

SPEC-04 §3/8/10 et SPEC-10 PRJ-01 exigent ensemble les versions historiques exactes et
l'avance conditionnelle CurrentPot. `MaterializePotVersion.prepareRecovery()` contrôle les
racines et sous-versions exactes, mais `JdbcPotVersionStore.evidence()` omet CurrentPot.
Le writer et `requireStored()` contrôlent déjà le pointeur dans la transaction de publication.
Un préfixe durable sans pointeur n'est donc pas produit par une finalisation L2 normale ; il
requiert corruption/injection ou restauration dissociée interdite. R9-04/09 impose néanmoins
de ne pas accepter une preuve incohérente : insuffisance d'oracle à reproduire avant correction.

SPEC-04 §5 impose explicitement de n'avancer Visibility que si V est supérieure à la garde ;
SPEC-10 PRJ-03 précise explicitement qu'un événement obsolète est terminalisé sans mutation
des entrées. SPEC-04 §6 impose CurrentBinding monotone ; SPEC-10 ID-02-F exige le rejeu hors
ordre, sans exiger d'état historique READ. Pour CurrentBinding, l'acceptation de l'état supérieur
exact est donc nécessaire à l'algorithme monotone, plutôt qu'une dispense de preuve historique
inventée. Aucun reçu métier par événement n'est exigé pour ces deux projections mutables.

SPEC-05 §3 et consolidation L2 R9-01/04/06/07/09 : le participant démontre l'effet métier
admissible et sa cohérence ; L2 vérifie publication pertinente, checkpoint de la bonne partition
et slot dans le snapshot READ final. SATISFIED + slot présent = NOT_COMPLETED ; SATISFIED +
checkpoint couvrant + slot absent = COMPLETED dérivé. V2 seule ne prouve jamais l'exécution
historique de V1. Sous les invariants d'enrôlement/suppression, checkpoint + absence démontrent
la terminalisation, y compris celle d'un traitement obsolète. Supprimer administrativement un
slot viole ces hypothèses ; aucune observation sans nouveau journal ne peut reconstituer ce
fait. Ne pas inventer un reçu durable pour distinguer deux états intentionnellement identiques.

Les sources exactes de V2 sont relues dans la préparation ; l'observation finale compare tout
l'ensemble visible ou toute l'EUI (identité, version, User, token). Une avance au-delà de la
version préparée donne UNAVAILABLE. Un checkpoint insuffisant donne INCONSISTENT ; un slot
présent reste non terminalisé. Les conformités archivées et le test de commit incertain ont
été lus ; leurs PASS restent attribués à leurs anciennes entrées, jamais rafraîchis implicitement.

## Verdicts et correction

**AUD-L4-01 confirmé comme insuffisance de l'oracle de recovery**, pas comme rupture
reproduite de l'atomicité du writer. PostgreSQL avant correction retourne COMPLETED avec
PotVersion et sous-versions exactes, checkpoint couvrant, slot absent et CurrentPot absent,
inférieur, à cible mal formée, à clé mixte ou invalide. Les mutations sont injectées avec
l'autorité administrative de fixture ; le pointeur pendant est normalement exclu par FK
(refus SQL vérifié avant retrait local de la contrainte dans ce seul schéma jetable).
La finalisation normale produit ces effets ensemble, avec contrôle de readiness et rollback.

Seul fichier de production modifié :
`app/infra-persistence-read-jdbc/src/main/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/v3/JdbcPotVersionStore.java`,
méthode `evidence()`. Après preuve exacte de PotVersion(P,V), elle lit CurrentPot dans le
même contexte READ final : absence = MISSING, version inférieure = DIVERGENT, version égale
= SATISFIED ; version supérieure admise seulement si sa cible existe et passe le décodage
canonique avec identité Pot/version conforme à la clé SQL. Corruption positive = DIVERGENT,
échec SQL reste indisponible selon L2. Le contrôle des sous-versions historiques exactes
reste dans `MaterializePotVersion.prepareRecovery()`, sans substitution par la cible supérieure.

La cohérence du pointeur contrôlée ici est sa référence vers une PotVersion typée de la même
clé ; elle ne transforme pas l'observation de V en audit exhaustif de toutes les publications
ultérieures. Ni scan du maximum global, ni reconstruction WRITE de toutes les versions,
ni exigence Balance/Visibility n'est ajouté. `prepareRecovery()` appelle déjà ce store :
aucune réécriture du participant ou du writer, aucun changement de publication/readiness.

**AUD-L4-02 Visibility : défaut non confirmé ; supersession explicitement autorisée.**
**AUD-L4-02 CurrentBinding : défaut non confirmé ; supersession conforme à la monotonie et
au rejeu hors ordre.** Dans chaque cas, V2 correcte satisfait le prédicat métier de V1 devenu
obsolète ; cela ne prouve pas son exécution passée. Le moteur distingue encore un slot présent
d'un slot terminalisé. Pas de correction de production pour ces deux participants, pas de
nouvel état durable, pas d'affaiblissement d'assertion. Les conditions exactes sont : événement
publié pertinent, identité stable, W≥V1, état exact à WRITE@W, photographie finale compatible
avec la préparation, checkpoint de la partition couvrant V1 et slot absent, avec tous les
invariants d'enrôlement/suppression de SPEC-05 §3 préservés.

## Contre-tests et diagnostics observés après correction

Toutes les observations suivantes appellent **le vrai `ConsumptionEngine.observe()`**, sources
WRITE, slots/checkpoints et projections sur PostgreSQL réel, avec les fixtures existantes.
Sauf mention contraire le checkpoint couvre l'événement. Les deux colonnes sont des
observations distinctes ; aucun verdict n'est déduit de la seule présence de contenu.

| État observé | Slot présent | Slot absent |
|---|---|---|
| PotVersion/sous-versions exactes, CurrentPot absent | NOT_COMPLETED | INCONSISTENT |
| Idem, CurrentPot < V | INCONSISTENT | INCONSISTENT |
| Idem, CurrentPot = V exact | NOT_COMPLETED | COMPLETED |
| Idem, CurrentPot > V avec cible typée cohérente | NOT_COMPLETED | COMPLETED |
| Idem, cible supérieure JSON mal formé, clé de version mixte ou référence invalide | INCONSISTENT | INCONSISTENT |
| CurrentPot > V valide, PotVersion historique absente | non rejoué | INCONSISTENT |
| CurrentPot > V valide, sous-version historique absente | non rejoué | INCONSISTENT |
| Visibility V2 supprimée, ensemble visible vide exact | NOT_COMPLETED | COMPLETED |
| Visibility V2, utilisateur visible divergent | INCONSISTENT | INCONSISTENT |
| CurrentBinding V2 inactif exact, puis V3 actif exact | NOT_COMPLETED | COMPLETED |
| CurrentBinding V3, divergence isolée token, User ou version | INCONSISTENT | INCONSISTENT |
| Projection mutable exacte, checkpoint absent/zéro injecté | INCONSISTENT | INCONSISTENT |

Les méthodes ajoutées dans `ReadParticipantsIntegrationPostgresTest` sont :

- `potVersionRecoveryRequiresCurrentPot` : sept états du pointeur × deux états de slot,
  puis deux absences historiques malgré un pointeur supérieur ; 16 diagnostics finaux.
- `visibilityRecoverySupersession` : DeletePot V2 différencie effectivement les ensembles V1/V2 ;
  slot V1 présent, absence injectée sans exécution V1, puis exécution L2 réelle de V1 obsolète,
  projection V2 préservée, divergence et checkpoint ; 9 diagnostics finaux.
- `currentBindingRecoverySupersession` : Unlink V2, traitement réel tardif V1 sans recul,
  Registration V3 active pour éprouver séparément token/User/version, slots et checkpoint ;
  13 diagnostics finaux. Les secrets de fixture ne sont pas exposés dans les diagnostics.
- `recoveryAffectedParticipantConformance` : appelle directement le corps existant de
  `participantConformance` pour **POT_VERSION, POT_VISIBILITY, CURRENT_BINDING seulement**.
  Les six valeurs du provider historique et toutes ses assertions restent intactes ; seul
  son commentaire sur le partage des preuves métier/slot est précisé.

Les helpers locaux `setRecoverySlot`, `recoveryObservation` et
`checkpointDoesNotProveProcessing` isolent injections SQL et diagnostics attendus/réels.
La suppression de slots/checkpoints, la désactivation des triggers et le retrait de FK ne
concernent que les schémas jetables administrés des tests ; aucune permission ou migration
de production ne change.

L'absence injectée de V1 jamais exécutée produit bien COMPLETED avec V2 exacte et checkpoint
couvrant. Ce résultat est conservé explicitement : c'est un contre-exemple **hors des invariants
du protocole**, impossible par le chemin L2 autorisé, et observationalement identique à une
terminalisation valide. Le détecter exigerait une autre hypothèse/journal, non prescrit par
les SPEC. Le test distinct exécute réellement V1 sous L2 et vérifie le même diagnostic avec
suppression atomique normale. Le checkpoint abaissé après publication est également une
injection invalide, pas un état produit par Discovery. Aucune nouvelle garantie contre une
administration arbitraire de READ n'est revendiquée.

UNAVAILABLE en cas d'avance concurrente au-delà de la version préparée ou d'indisponibilité
SQL est conservé par inspection du code inchangé ; pas de campagne de panne/concurrence
supplémentaire ni de PASS neuf pour ces branches.

## Commandes, attribution et mesures

[Manifeste structuré, diagnostics et argv exacts](../testing/evidence/L4-Recovery/Manifest.json),
[preuve d'identité des entrées](../testing/evidence/L4-Recovery/Input_Attribution.json) et
[archives autonomes](../testing/evidence/L4-Recovery/README.md).

Depuis le checkout isolé `/private/tmp/pocoma-l4-recovery`, cloné de la branche locale sans
artefacts d'éditeur, deux commandes ont été exécutées, une fois chacune :

```sh
python3 -B docs/testing/evidence/L4-Recovery/run-targeted.py --workspace /private/tmp/pocoma-l4-recovery --selection counterexamples --output /private/tmp/pocoma-l4-recovery-before
python3 -B docs/testing/evidence/L4-Recovery/run-targeted.py --workspace /private/tmp/pocoma-l4-recovery --selection qualification --output /private/tmp/pocoma-l4-recovery-after
```

L'opérateur réutilise `scripts/testing/verify.py` inchangé, avec sélection positive locale en
mémoire : aucun changement de registre ni rejeu des neuf campagnes. Les commandes Maven
test réellement exécutées depuis `app/` sont les suivantes (le préfixe de classe est
`com.kartaguez.pocoma.runtime.task.v3.ReadParticipantsIntegrationPostgresTest`) :

```text
./mvnw --batch-mode --no-transfer-progress -pl runtime-task-consumption-worker -am -Dtest=com.kartaguez.pocoma.runtime.task.v3.ReadParticipantsIntegrationPostgresTest#potVersionRecoveryRequiresCurrentPot+visibilityRecoverySupersession+currentBindingRecoverySupersession -Dsurefire.failIfNoSpecifiedTests=false -Dpocoma.verification.id=1f22af87f0f74803a5ca767a7e82eb37 -Dpocoma.verification.reports=/private/tmp/pocoma-l4-recovery-before/reports test
./mvnw --batch-mode --no-transfer-progress -pl runtime-task-consumption-worker -am -Dtest=com.kartaguez.pocoma.runtime.task.v3.ReadParticipantsIntegrationPostgresTest#potVersionRecoveryRequiresCurrentPot+visibilityRecoverySupersession+currentBindingRecoverySupersession+recoveryAffectedParticipantConformance+uncertainCommitHasNoSpeculativeSettlement -Dsurefire.failIfNoSpecifiedTests=false -Dpocoma.verification.id=04f7b28344ff4fa1a198b8052f0a069d -Dpocoma.verification.reports=/private/tmp/pocoma-l4-recovery-after/reports test
```

| Run | SHA réellement testé, propre | JUnit tests/F/E/skip | Maven observé (s) | Campagne observée (s) | Résultat |
|---|---|---|---:|---:|---|
| Avant correction | `23e9c1758b36d166e8e0ad02a5bf77e715d32a06` | 3/1/0/0 | 17,493 | 20,789 | FAIL attendu, AUD-L4-01 reproduit ; deux méthodes AUD-L4-02 PASS |
| Après correction | `f30891541787decf308675aeabd57a0577db11c6` | 5/0/0/0 | 31,998 | 35,148 | PASS borné |

Le premier SHA ajoute uniquement tests/documentation à la production de `b3b7b8c4` ; aucun
ancien PASS n'est transformé en preuve fraîche. Le FAIL initial comporte neuf assertions
discriminantes en échec dans cinq états du pointeur. `assertAll` conserve les 14 observations
de la matrice initiale ; les deux contrôles d'absence historique qui suivent ne sont atteints
qu'au run final. Les 38 diagnostics finaux sont archivés avec leurs résultats exacts.

Le total JUnit final est **5**, pas 7 : le wrapper de conformité est une invocation contenant
trois appels explicites du corps historique. Les traces distinguent les trois participants,
les modes missing/afterComplete/success et les témoins PID/XID. Ces témoins réaffirment
l'atomicité publication/slot ; la conformité POT_VERSION inclut CurrentPot dans la transaction.
`uncertainCommitHasNoSpeculativeSettlement` est rejoué car son oracle PotVersion évolue :
BEFORE_COMMIT = UNKNOWN/NOT_COMPLETED, AFTER_COMMIT = UNKNOWN/COMPLETED, aucun règlement
spéculatif de capacité. Aucun autre corps d'intégration n'est sélectionné.

Prévision nominale **UNKNOWN**, distincte des mesures ; ni plafond ni arrêt sur dépassement.
Somme des deux campagnes : **55,937 s**, dont **49,491 s** de processus Maven test, incluant
le FAIL initial. Ce n'est pas une mesure du temps total de développement. Les durées de
découverte sont séparées dans les reçus ; chaque run lance aussi `help:effective-pom` pour
la même fermeture et `./mvnw -v` (six processus Maven au total), avec argv/processus dans
les bruts. Aucun clean/install, aucun retry automatique, aucun timeout métier modifié.

Environnement observé : Temurin 21+35, Maven 3.9.14, Docker 29.9.2, PostgreSQL **17.11**, image
épinglée `postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73`.
Sources recompilées dans le checkout neuf au premier run ; second run incrémental après
modification. Dépendances/image déjà en cache, aucun coût froid garanti. Les entrées Java/SQL
livrées sont identiques au SHA testé ; seule la fin de ligne du wrapper Windows non exécuté
`app/mvnw.cmd` diffère entre checkout local et clone conformément à `.gitattributes`.

Les anciens reçus L4/I3–I7 (integration-01, final-integration-recovery et final-integration-filter)
ont été examinés, sans modification ni nouveau PASS attribué. Aucun store/contrat supplémentaire
n'est rejoué : le changement de lecture est intégralement exercé via son vrai consommateur L2,
et les writers, contrats de données et permissions ne changent pas. Suites L2 historiques,
six conformités complètes, neuf campagnes, gate Architecture et full reactor **NOT RUN**.

## Livraison, réserves et clôture de la mission

Traversée inattendue de slice : **NONE**. Impact observé identique à la déclaration : un store
de preuve READ, ses tests de composition et des documents/preuves L4. Aucune dépendance,
frontière transactionnelle, source, identifiant ou famille modifiée. Aucun invariant métier
affaibli. Gate globale supplémentaire **NOT REQUIRED** ; full reactor **NOT RUN**, conformément
au mandat strictement ciblé et au maintien des gates à I8.

Les commits applicatifs sont `23e9c175` (contre-tests préalables) puis `f3089154` (correctif).
Le commit documentaire qui porte les archives ne modifie aucune entrée applicative ; le PASS
reste attribué au SHA testé `f3089154`. Publication sans force sur `feat/v3-architecture` ;
le SHA documentaire final et la synchronisation distante sont constatés dans la restitution
après push, sans prétendre enregistrer un commit dans son propre contenu.

Réserves conservées : confiance dans les invariants d'administration/enrôlement/suppression
L2, absence de preuve exhaustive contre la corruption administrative arbitraire, branches
UNAVAILABLE non rejouées ici, paramètres productifs et campagnes globales non qualifiés
par cette mission. Les réserves historiques L1/L2/L3/L4 demeurent attribuées à leurs rapports.

Statuts inchangés : L4/I1 **CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS** ; L4/I2
**CLOSED — LOCAL BOUNDED PASS** ; **I2-QUAL-01 RESOLVED**. L4/I3–I7 : implémentation livrée,
qualification locale bornée. Gates canoniques et Architecture reportées à I8 ; certification
SPEC-10 **non acquise** ; activation productive **interdite avant I8**. Aucun résultat de ce
correctif ne clôt L4 globalement ou ne constitue une certification globale.

| Constat | Verdict | Correction | Tests | Statut |
|---|---|---|---|---|
| AUD-L4-01 | Insuffisance d'oracle confirmée sous injection ; état non produit normalement | CurrentPot et cible inclus dans `evidence()` | Matrice pointeur/historique, conformité POT_VERSION, commit incertain PASS | Résolu localement |
| AUD-L4-02 — Visibility | Supersession conforme, pas de preuve individuelle exigée | Conditions et hypothèses explicitées, production inchangée | Ensemble V2, slot/checkpoint, vrai V1 obsolète, conformité PASS | Instruit, défaut non confirmé |
| AUD-L4-02 — CurrentBinding | Supersession conforme à la monotonie | Conditions et hypothèses explicitées, production inchangée | V2/V3, token/User/version, slot/checkpoint, vrai V1 obsolète, conformité PASS | Instruit, défaut non confirmé |

**CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS**
