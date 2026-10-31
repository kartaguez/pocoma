# L3/I4 — Exécution R-ID-POT

**IMPLEMENTED — READY FOR INDEPENDENT AUDIT**, avec réserves explicitement
conservées. Campagne du 31 octobre 2026, mandat humain de développement et
publication intégrale I4 avec A1 accepté. R-ID-POT interne et A1 prouvés ; les deux
gates canoniques restent FAIL. Aucune acceptation indépendante I4 n'est prononcée.

## Référence et portée déclarée avant code

- Branche : `feat/v3-architecture`, dépôt `kartaguez/pocoma`.
- SHA initial/local/distant : `def1c11eda27bd5a846c9d7563b6a2c545652e9e`.
- Aucun delta depuis le SHA du mandat ; worktree/index propres ; autres worktrees
  inventoriés et préservés. Le plan publié décrit sa préparation au parent `a336d2b7`.
- Impact attendu : exactement l'adaptateur PRIMARY et les deux compositions
  partagées du §8 du plan I4 ; six fichiers de tests et le registre de campagne.
- Slice primaire COMMAND ; secondaires canoniques NONE ; racine complémentaire
  `runtime-monolith`. Risques : Identity, autorisation, transactions/atomicité,
  concurrence/recovery, assemblage runtime, Architecture.
- Architecture globale REQUIRED (exception technique nominative de dépendance).
- Full reactor NOT REQUIRED / NOT RUN : étape bornée, sans restructuration Maven,
  clôture de Wave ou intégration main. FOUNDATION autonome non requise tant que
  son infrastructure ne change pas ; contrôles transitifs distingués.
- PostgreSQL CURRENT réel, V3 WRITE V1–V6 et READ technique V1. Baseline TRUSTED
  NONE. Aucune migration nouvelle, modification de migration ou de grant autorisée.
- Interdits : domaine/transitions/ports Pot et Identity, moteurs et classifier L2,
  propriétaires/transactions, slots/claims/leases/R9, budgets/pools, suffixe et
  publication, HTTP/READ, migrations, grants et contrats cités par le mandat.
- Escalade : contradiction nouvelle, contrat/frontière interdite indispensable,
  migration imprévue indispensable, invariant impossible ou défaut bloquant non
  isolable : diagnostic avant toute extension. Erreurs ordinaires corrigées localement.

## A1 — Décision humaine appliquée

La formulation stricte du [plan L3 §§3.2 et 7.2](L3_Identity_Inventory_and_Implementation_Plan.md)
exigeait « aucun accès à l'histoire, au curseur, au verrou ou aux révisions Pot »
et « zéro accès Pot ». Le mandat GO I4 du 31 octobre accepte explicitement
la précision [A1 du plan I4 §16](L3_I4_Implementation_Plan.md#16-arbitrages-bloquants-avant-go).
Pour un témoin négatif stable, l'interdiction porte sur toute lecture métier,
reconstruction, décision de droits, protection/verrou métier, mutation ou accès
métier aux révisions Pot. Seul reste autorisé le SELECT technique préexistant
`JdbcPotCommandStore.requireCompletionReady` sur `v3_pot_version`, qui vérifie
qu'une tentative déclarée sans effet n'a pas produit de version Pot. Il ne
résout aucune autorisation, ne prend aucun verrou de ligne métier et ne mute rien.
La garde reste inchangée ; aucun amendement du canon ni des SPEC n'est effectué.

Le SELECT conservé et identifié séparément dans les traces est :

```sql
select coalesce((select writer_xid=pg_current_xact_id()
  from v3_pot_version where pot_id=? order by version desc limit 1),false)
```

Il ne comporte ni `FOR UPDATE`, ni `FOR SHARE`, ni acquisition advisory ; les
probes physiques complètent ce constat de forme SQL.
Les traces distinguent ce SELECT des accès métier ; le test adverse injecte une
version dans la transaction et exige le refus de completion avec rollback total.

## Réserves conservées

R-L2-01…05 ; G1/G2 INCONCLUSIVE ; G3 NOT PASSED / NOT COMPLETED.
I3 R1 : composition JPA livrée qualifiée, contre-exemple JDBC manuel hors périmètre.
I3 R2 : profil DEV fermé et administrateurs/propriétaires de confiance, sans
surveillance continue des ACL. I3 R3 : serveur PostgreSQL et logs Docker qualifiés,
pas tous les collecteurs externes. I3 R4 : FAIL historiques `i5_r05`/`i5_r12`
et absence historique de gate Architecture canonique conservés. Les nouvelles
preuves ne réécrivent aucun verdict antérieur. Ni I5/L3, ni ID-02 externe,
HTTP/READ, SLO SPEC-09 ou distribution L8 certifiés par cette campagne.

## Architecture effectivement livrée

`JdbcIdentityPotActorAuthority` implémente le port existant, sur la source et le
propriétaire Business injectés dans Command. L'observation exige RR/RO et la
ressource Spring active ; sa jointure externe lit la clé exacte issuer/subject,
le curseur et l'état correspondant. Un curseur positif sans état est une faute
technique. Le calcul commun conserve la version EUI même avec acteur absent et
ne résout un User que pour cette EUI et son token courant exactement égal.
Aucun token n'entre dans le Witness. Les erreurs JDBC Identity sont expurgées,
avec SQLSTATE conservé sans DETAIL ni cause sensible.

La finalisation déléguée acquiert une seule fois le singleton EUI avec
`JdbcIdentityAuthority.lockInCanonicalOrder`, relit son état sous protection,
force les deux contraintes structurelles nommées avant retour, puis compare
les Witness complets. `true` signifie stabilité du témoin ; un acteur absent
stable reste INVALID_BINDING. Un changement de version, y compris négatif vers
négatif ou re-Link vers le même User, donne la Collision existante et sa reprise
L2. Aucun owner, port, cache, protocole de verrouillage ou retry supplémentaire.

Le mode `real` autorise Identity seule, Identity+Command ou Command seule, avec
fixture false et ensembles exacts de limites/partitions. Le mode fixture reste
explicitement isolé. Le préflight Identity s'applique à `real` indépendamment du
flag IdentityRequest, puis vérifie Business avant assemblage Command réelle.
Worker et Monolithe conservent leur propriétaire JPA et les quatre pools existants.
La composition construit localement le fournisseur choisi ; aucun fallback.

La règle d'architecture conserve l'interdiction Pot pour les autres classes
techniques Identity. Seul ce nouvel adaptateur peut dépendre de `UserId` Pot.
Les contrôles de transaction interdite, de writer unique EUI et de délégation
restent présents. Aucun POM ou graphe Maven modifié.

## Fichiers de code et tests

Les dix fichiers du commit de code sont exactement les neuf fichiers Java du
§8 du plan et `scripts/testing/verification-campaigns.json` :

- Productif créé :
  `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/JdbcIdentityPotActorAuthority.java`.
- Productifs modifiés :
  `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionWorkerSettings.java`,
  `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionWorkerConfiguration.java`.
- Tests créés : `IdentityPotAuthorityPostgresTest` dans PRIMARY et
  `V3IdentityPotCommandPostgresTest` dans le Worker.
- Tests étendus : `V3PotCommandCompositionPostgresTest`,
  `V3IdentityRequestCompositionPostgresTest`, `V3IdentityRequestMonolithPostgresTest`,
  `V3IdentityAuthorityArchitectureTest` dans leurs modules existants.
- Registre : ajout des deux classes nouvelles à CHANGE Identity, donc à
  INTEGRATION cumulative ; FAST et quarantaines inchangés.

Les chemins complets et SHA-256 sont dans le manifeste. La liste documentaire
finale comprend ce rapport, le manifeste, le dossier de preuves et l'actualisation
factuelle du plan directeur. Aucun autre fichier productif modifié ; aucun
franchissement inattendu de slice. `JdbcPotCommandStore`, l'autorité Identity,
les domaines/ports/moteurs, propriétaires, migrations et scripts de grants restent
identiques à la référence initiale.

## Oracles PostgreSQL et périmètre réellement prouvé

| Oracle I4 | Preuve et issue au code gelé |
|---|---|
| Absent avec/sans ancre, libre, mauvais token à version inchangée | Witness exact et protect=true ; moteur réel terminalise INVALID_BINDING une seule fois ; aucune cession artificielle. A + R PASS |
| Token valide/étranger et clé opaque | Deux EUI du même User avec tokens distincts et croisés ; issuer, casse et Unicode distincts ; espace ajouté au token refusé. A + racines PASS |
| Changement réel | 0→1, libre→lié, négatif→négatif, valide→libre, re-Link vers même User : Collision, PENDING sans effet puis nouvelle acquisition après retryAfter DB avec intention/token d'origine. A + R PASS |
| R-ID-POT | Registration réelle via IdentityRequest/L2 ; CreatePot puis UpdatePotDetails ; créateur égal au User autoritatif ; ancien/étranger refusé ; User tiers lié mais sans droits Pot reçoit FORBIDDEN. C + Worker + Monolithe PASS |
| Sérialisation | Barrières, PID/bloqueur serveur et relecture indépendante ; détails ci-dessous. A + R PASS |
| Ordre unique | Singleton Pot et entrées inversées/non-ASCII des demandes à deux EUI ; paires INSERT/FOR UPDATE canoniques ; vecteurs et courses I2 réexécutés ; règle de délégation. A + I2 + X PASS |
| Ancre zéro/suffixe | Ancre technique seule sans User/état/événement Identity ; contraintes nommées avant retour protect et avant compteur ; garde de fermeture/poison conservée. A + R PASS |
| Atomicité/rollback/R9 | XID complet de version Pot et xmin des lignes résultat/révisions/événements concordants ; suffixe limité aux compteurs/positions ; sept coupures et deux issues physiques de COMMIT ; nouvelle acquisition après échéance, jamais second finalize sous ancien handle. R + C PASS |
| Histoire et abandon | Re-Link renouvelle le token ; dernier Unlink conserve le User ; nouvelle Registration donne un autre User sans récupérer les droits du créateur abandonné ; restart sans doublon. A + racines PASS |
| Modes et confidentialité | Configurations complètes positives, refus Command seule pour privilèges excessifs, fixture, contrat V6 incomplet, objet indirect et logging dangereux ; tests JPA/holder I3 conservés dans les deux racines. A + W/M + X PASS |
| A1 | SQL métier Pot absent ; SELECT technique isolé exactement, sans advisory lock ni verrou de ligne Pot par probes NOWAIT après protection, après contrôle et après positions ; injection illicite détectée avec rollback total. R PASS |

Concurrence exécutée : Command protégeant avant Registration sur absence, ancre
zéro et état libre, avec commit/rollback (6 variantes) ; Command avant Link/Unlink
(4) ; Identity avant Command pour Registration absente/zéro/libre, Link et Unlink,
avec commit/rollback (10). Les trois issues Command CHANGED/UNCHANGED/REJECTED
retiennent leur protection EUI après completion et fermeture jusqu'au commit ou
rollback physique (6). Les courses entre observation et finalisation utilisent les
vrais producteurs Identity et le classifier de la factory ; les reprises
attendent les deadlines PostgreSQL sans les réécrire. Fencing réel : claim repris,
claim expiré et transfert de partition sous claim valide (3). Les PID, XID,
bloqueurs et relectures sont conservés sans token.

Coupures Command : après protection EUI, révision, résultat, TERMINAL, suppression
du slot par completion, préfixe de complétion et positions (7). Les fautes ne
produisent aucun rejet métier. Le proxy PostgreSQL existant est réutilisé par
réflexion depuis son package de test, sans duplication ni modification : coupure
avant COMMIT et COMMIT réellement confirmé par le serveur avec ACK perdu. R9
relit l'état durable ; le budget reste à zéro, et la reprise éventuelle attend
l'expiration du claim puis emploie un nouveau token de claim.

L'admission Command est une insertion PENDING interne contrôlée par le harness
administratif ; l'injecteur Identity seul garde ses grants historiques. Discovery
et Engine exécutent ensuite les véritables parcours. Le propre bindingToken est
récupéré uniquement dans le harness confidentiel. Aucune API ni projection de
substitution n'est créée. Les snapshots exportent des empreintes et les champs
non sensibles, jamais les valeurs de tokens.


## Campagnes et provenance

SHA exécuté pour les preuves finales :
`145f42731a4e35488e9aaa56a7de3c3f453a9231`.
Les seuls changements de worktree pendant ces preuves sont documentaires et de
constitution du dossier ; aucune entrée applicative ou recette modifiée. Les
collecteurs spécifiques sont conservés aux octets utilisés et leur intégrité
est vérifiée séparément. Les SHA de chaque entrée, outils, settings non secrets,
SQL externes et état Git sont dans les JSON de provenance compressés sans perte.

Commandes opérationnelles exactes depuis la racine :

```sh
python3 -B scripts/testing/verify.py FAST identity --dry-run
python3 -B scripts/testing/verify.py CHANGE identity --dry-run
python3 -B scripts/testing/verify.py INTEGRATION identity --dry-run
python3 -B scripts/testing/verify.py FAST identity --output /private/tmp/pocoma-l3-i4-fast-final
python3 -B scripts/testing/verify.py CHANGE identity --output /private/tmp/pocoma-l3-i4-change-final
python3 -B scripts/testing/verify.py INTEGRATION identity --output /private/tmp/pocoma-l3-i4-integration-final
POCOMA_PROVENANCE_EVIDENCE=/private/tmp/pocoma-l3-i4-selector-cli-final python3 -B -m unittest discover -s scripts/testing -p 'test_verification*.py'
python3 -B docs/testing/evidence/L3-I4/collect_architecture.py /private/tmp/pocoma-l3-i4-architecture-bounded
```

Les redirections des dry-runs et journaux conservés sont référencées dans le
dossier. Chaque recette retient aussi son argv Maven développé complet, son
inventaire attendu depuis les sources et l'effective POM de la même sélection.
CHANGE : 20 classes/149 invocations ; INTEGRATION : 28 classes/187 invocations.
Aucune Architecture ajoutée à une recette Identity ; quarantaines nominatives
inchangées. Les 163 tests Architecture sont les 28 classes du module entier,
avec revue bornée de six classes portant des annotations de fixtures Spring
sans rôle de découverte Jupiter ; ni test, ni assertion, ni framework modifié.

| Campagne finale | Tests | Failure | Error | Skip | Qualification |
|---|---:|---:|---:|---:|---|
| FAST Identity | 65 | 0 | 0 | 0 | PASS borné |
| CHANGE Identity | 149 | 0 | 0 | 0 | PASS borné |
| INTEGRATION Identity | 187 | 0 | 0 | 0 | PASS borné |
| Module Architecture complet, filtre explicite | 163 | 0 | 0 | 0 | PASS borné ; pas gate canonique |
| Contrôles Python du registre/provenance | 40 | 0 | 0 | 0 | PASS ; vrais replays CLI dans checkout jetable |
| Gate COMMAND + Monolithe non filtrée | 1 789 | 1 | 0 | 1 | FAIL |
| Gate Architecture non filtrée | 1 892 | 2 | 0 | 1 | FAIL ; module Architecture SKIPPED après échec amont |

Les contrôles d'outillage réexécutent le contrat FOUNDATION dans leurs checkouts
jetables (nominal 28 et contre-exemples de collecte). Leurs reçus restent distincts
d'une campagne autonome FOUNDATION de qualification I4, non requise ici.

Les deux gates natives sont exécutées séparément et en série depuis `app/`.
Voici les argv réellement exécutés, sans filtre ou exclusion :

```sh
./mvnw --batch-mode --no-transfer-progress -pl runtime-command-consumption-worker,runtime-monolith -am -Pverification-provenance -Dpocoma.verification.id=ef1fd20f15364747b0bf0ef71053857d -Dpocoma.verification.reports=/private/tmp/pocoma-l3-i4-native-command/reports test
./mvnw --batch-mode --no-transfer-progress -pl architecture-tests -am -Pverification-provenance -Dpocoma.verification.id=a639e9be6b4f4641a139d1a703caee82 -Dpocoma.verification.reports=/private/tmp/pocoma-l3-i4-native-architecture/reports test
```

Le collecteur conserve l'exécution native demandée même lorsque certaines formes
JUnit historiques ne sont pas supportées par la découverte du sélecteur. Ces
limites restent explicites (46 pour COMMAND + Monolithe, 67 pour Architecture) ; aucune couverture
canonique complète ou PASS n'en est inféré. L'ID/module/producteur de chaque XML,
son attribution au module source et ses compteurs sont contrôlés ; les réponses
manquantes/skips et formes non qualifiées restent conservées.

La gate COMMAND + Monolithe rapporte un seul échec JUnit :
`V3WorkerResourcesPostgresTest.i5_r12`, 95,051 s, `runtime condition deadline`,
à la ligne 896 via fermeture du Runtime/campagne temporelle. Ce même témoin
historique reste FAIL ; aucune correction de timeout, budget ou L2 n'est faite.
`i5_r05` est PASS dans cette observation (53,6 s), sans lever sa réserve historique.
Le skip est `TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`,
propriété `step3b.measure` absente. Les tests I4 de la gate sont PASS, y compris
les 11 adaptateur, 9 Identity/Pot, 10 Worker Identity, 7 Monolithe Identity et
2 composition Pot ; ils isolent le raccord réel du banc fixture temporel I5.
Le diagnostic de retrait d'une JVM Surefire après `System.exit(0)` est également
conservé dans le log PRIMARY : 624 tests, 0 failure/error/skip, puis poursuite de
Maven. Il ne s'agit pas d'un succès global ni d'une correction de disponibilité.

Diagnostics intermédiaires conservés : FAST initial 65 PASS ; CHANGE initial
128 tests/2 failures/0 error/0 skip (instrument de test choisissant un ancien PID) ;
premier feedback des fichiers modifiés 16 tests/0 failure/2 errors/0 skip
(admission Command tentée sous l'injecteur Identity, sans grant Command) ;
feedback corrigé 36 PASS. Les corrections restent dans les six fichiers de tests,
sans élargir les grants ni le code productif. Ces sources intermédiaires sales
ne sont pas archivées comme diff complet : résultats diagnostiques conservés,
non attribués au SHA final. Les preuves finales propres ci-dessus les remplacent
pour qualifier I4, sans effacer leurs FAIL. Le premier contrôle d'outillage sous
sandbox Docker restreint échoue en setup (30 contrôles/1 erreur de classe) ; les
40 contrôles complets réussissent ensuite avec accès Docker et rétention brute.

La recompilation propre de la fermeture déclarée précède les preuves finales :

```sh
cd app
./mvnw -pl runtime-command-consumption-worker,runtime-monolith,architecture-tests -am clean test-compile
```

Elle supprime les sorties préexistantes susceptibles de reproduire l'écart
source/bytecode I3, sans `clean install` ni full reactor. Inventaire de 1 898
classes et sorties `javap` conservés. Les blobs Git bruts servent à comparer les
entrées exécutées : `git archive` peut appliquer CRLF à `mvnw.cmd`, alors que les
exécutions Unix et le blob Git utilisent LF. Aucun wrapper modifié pour cela.
Les reçus bruts sont conservés dans Git, sans stockage externe supposé. Les
[64 reçus de variantes](../testing/evidence/L3-I4/Variant_Receipts.json) relient les
assertions/observations aux lignes exactes des traces ; les libellés dérivés de
la séquence fixe des appels opaques sont distingués des marqueurs runtime.


Une revue de collecte nominative corrige le compteur COMMAND initial de 1 788
à **1 789** : `CurrentTransactionManagerPostgresTest` est une seconde classe de
test top-level, non publique, déclarée dans `JpaTerminalSequencingPostgresTest.java`
(ligne 252). Son unique méthode standard `effectiveManagerAndSharedJdbcConnection`
est PASS dans un XML frais portant les bons ID/module/producteur. Le mapping
initial par nom de fichier omettait cette classe ; l'exécution n'était pas absente.
Le reçu initial et les octets XML restent inchangés. `qualify_native.py` et le
reçu de revue attribuent cette seule classe au module source exact, recalculent
les compteurs et conservent toutes les autres limites de découverte. Aucune
relance Maven ni extension du framework n'est nécessaire ; FAIL et skip inchangés.


La gate Architecture indépendante a effectivement été exécutée après le FAIL
COMMAND, avec son ID neuf, sa fermeture de 60 modules et ses rapports propres :
**1 892 tests, 2 failures, 0 error, 1 skip, FAIL**. `i5_r05` échoue en 17,069 s sur
`B missing independent available window a5000000-0000-4000-8000-000000000002/0`,
via `I5TemporalV2Postgres.Journal.verify` puis `campaign` ; `i5_r12` échoue en
95,137 s sur le même `runtime condition deadline`. Le skip exploratoire est le
même. Le module `architecture-tests` est SKIPPED par Maven après l'échec de sa
dépendance Worker. Les **163 tests du module Architecture exécutés séparément**
restent PASS au même code gelé, avec bytecode frais ; ils ne remplacent ni ne
requalifient cette gate canonique FAIL. Les suites I4 effectivement atteintes
dans les deux gates natives sont PASS. Les durées de processus observées sont
3 211,070 s pour COMMAND + Monolithe et 3 242,034 s pour Architecture ; elles ne
constituent aucune mesure de SLO applicatif.

La même revue de classe secondaire corrige le compteur Architecture initial de
1 891 à 1 892, sans changer les deux failures ni le skip. Les évaluations initiales,
les deux revues nominatives et leurs évaluations corrigées sont toutes conservées.
Ces anomalies de disponibilité concernent les bancs temporels I5 inchangés en mode
fixture ; le nouvel adaptateur réel n'y est pas sélectionné. Leurs assertions,
budgets et mécanismes restent inchangés par rapport à la référence. L'isolation
par les preuves réelles I4 réussies ne résout pas leur cause physique et ne lève
aucune réserve L2/I3. Aucune relance inchangée n'est faite pour fabriquer un PASS.

Commandes de qualification des octets archivés, sans nouvelle exécution Maven :

```sh
python3 -B docs/testing/evidence/L3-I4/qualify_native.py /private/tmp/pocoma-l3-i4-native-command
python3 -B docs/testing/evidence/L3-I4/qualify_native.py /private/tmp/pocoma-l3-i4-native-architecture
python3 -B docs/testing/evidence/L3-I4/verify_evidence.py
git diff --check
```

La comparaison finale des **1 898 classes** avec l'inventaire après compilation
propre trouve **0 classe nouvelle/modifiée/manquante**. Les empreintes des entrées
applicatives de chaque preuve finale correspondent aux blobs Git du code testé ;
aucune entrée applicative n'est modifiée après les preuves. Les hashes des scripts
spécifiques employés et des reçus de revue sont également conservés. Le contrôle
indépendant vérifie les tailles/SHA-256, membres exacts des archives, provenance
ID/module/producteur, compteurs XML et lignes des reçus de variantes.

Base de données : PostgreSQL réel 17.11. INTEGRATION Identity utilise uniquement
les migrations CURRENT V3 WRITE V1–V6 / READ technique V1. Les preuves Architecture
et gates natives exécutent aussi leurs migrations historiques transitives,
observées jusqu'à V31 dans les journaux ; leurs libellés et nombres sont dans
`Migration_Observations.json`. Pas de baseline TRUSTED invoquée, pas de campagne
historique autonome, pas de migration/index/contrainte/grant productif modifié.

## Livraison, limites et reprise

| Référence | Identité |
|---|---|
| SHA initial et plan publié | `def1c11eda27bd5a846c9d7563b6a2c545652e9e` |
| SHA testé et code destiné à la publication | `145f42731a4e35488e9aaa56a7de3c3f453a9231` |
| Commit du dossier de livraison | `git log -1 --format=%H -- docs/v3/L3_I4_Proof_Manifest.json` |
| Branche publiée | `feat/v3-architecture` sur `kartaguez/pocoma` |

Le commit documentaire porte ce rapport, le manifeste, les preuves et le plan
directeur factuel ; son SHA n'est pas inscrit de manière autoréférente dans ses
propres octets. Le reçu final de livraison fournit le SHA exact après commit/push.
Le manifeste énumère tous les fichiers publiés et leurs empreintes applicables.
La tête distante contrôlée avant publication reste `def1c11e…`, sans avancée
concurrente ; publication sans reset/stash/clean Git, sans force push. Les travaux
et autres worktrees préexistants sont préservés. La branche, l'inventaire staged,
`git diff --check`, la tête distante et la synchronisation sont contrôlés avant
remise du dossier ; aucune réussite d'audit indépendant n'en est déduite.

Slices déclarées/exécutées : **COMMAND**, racine complémentaire **Monolithe** ;
secondaires canoniques **NONE**. Franchissement productif inattendu : **NONE**.
Gate globale Architecture **REQUIRED / RUN / FAIL**, preuve séparée de module
**163 PASS**. Full reactor **NOT REQUIRED / NOT RUN** pour cette étape bornée,
sans restructuration de modules, clôture de Wave ou intégration main. Pas de gate
FOUNDATION autonome de qualification I4 ; replays d'outillage explicités ci-dessus.
Réserves héritées conservées, plus limites de découverte globale conservées dans
les reçus ; aucun défaut nouveau du raccord I4 n'est identifié dans ce périmètre.

Le jalon **R-ID-POT interne est implémenté et prouvé**, avec l'arbitrage A1 respecté.
La décision suivante appartient à l'audit indépendant puis à une décision humaine
distincte. I5/L3 ne sont pas clos ; aucune certification globale ou production.

**IMPLEMENTED — READY FOR INDEPENDENT AUDIT.**
