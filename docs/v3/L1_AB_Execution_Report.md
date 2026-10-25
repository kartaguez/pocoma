# L1-A + L1-B — Livraison au checkpoint d’audit

2026-10-25 — Dépôt `kartaguez/pocoma`, branche de publication `feat/v3-architecture`.

**Verdicts techniques : L1-A PASS ; L1-B PASS, sur le périmètre interne obligatoire décrit ci-dessous. Audit indépendant du code, de l’architecture, de la migration et des preuves en attente. L1 n’est pas définitivement accepté. STOP après publication A/B ; aucune implémentation L1-C, L1-D ou L1-E.**

Les gates Maven obligatoires ont tous terminé avec code 0. La couverture globale complète des gates consommateurs/architecture est **INCONCLUSIVE**, avec un skip exploratoire V2 explicitement conservé. Toutes les méthodes obligatoires A/B/ArchUnit ont été exécutées sans failure/error/skip. Aucun PASS ne repose sur la compilation seule ; aucun résultat L0/V2 n’est transféré comme certification V3.

## Référence, autorisation et commits

Départ : `8221692287420cd69feebd170df7c9ea6c279fdd`, identique à la tête distante vérifiée avant modification. Plan approuvé : [L1_Design_and_Verification_Plan.md](L1_Design_and_Verification_Plan.md), notamment §§15–18 et 21–24. L’autorisation explicite du 2026-10-25 couvre A puis B après audit du plan ; elle ne couvre pas C/D/E. Les SPEC-01…10 et la politique du réacteur restent normatives. Le plan publié est conservé comme artefact de conception ; le présent rapport et le canon donnent le statut d’exécution actuel.

**Dernier commit d’entrées exécutables, testé par les trois gates finaux B : `887c24f3f266304e46c281cc61ba170f35d4de9a`.** Les commits documentaires de publication ne doivent modifier que les exclusions explicites du collecteur, jamais ces entrées. La publication est une avance rapide, sans force ; le contrôle distant post-publication est conservé séparément puis ajouté au dossier documentaire.

| SHA | Contenu |
|---|---|
| `8a33b2cb2eaaac767c5fc1d25fbeffe9b4a119f3` | feat(v3-l1-a): implement immutable Pot transitions and nested-port guards |
| `dbec5a8ea44fa6406f208ab10e8b0cbb9579abef` | test(v3-l1-a): account for declared Windows wrapper line endings |
| `23f18a0126bf4ccd668aa1ad9f5d229d90666544` | test(v3-l1-a): keep evidence collection free of generated Python inputs |
| `4eb9c976a8051f4b35d9cf222456d74702e4e829` | docs(v3-l1-a): record bounded PASS and archive fresh gate evidence |
| `f72a9f30a43fedf733b258acd9357127a6c8af62` | feat(v3-l1-b): persist exact Pot histories and authoritative snapshot facts |
| `887c24f3f266304e46c281cc61ba170f35d4de9a` | fix(v3-l1-b): retain full transaction epoch when guarding version revisions |

## Fonctionnalités effectivement livrées

**A — domaine pur :** huit composantes métier immuables, identités stables réutilisées, `Versioned<T>` et versions locales indépendantes ; rationnels BigInteger canoniques, poids strictement positifs, montants non négatifs et conversion décimale exacte ; participants identifiés et poids spécifiques facultatifs ; intentions et transitions regroupées par responsabilité Pot/Shareholders/Expenses. Créations, modifications Details/Weight/Financials, rattachement/détachement ordinaire et trois suppressions sont implémentés. Droits courants, activité, obsolescence avant no-op, références existant à l’observation, atomicité logique multi-cibles et débordement des compteurs sont contrôlés. `TransitionResult<T>` distingue rejet métier, absence d’effet et proposition typée à V+1 ; une proposition n’est pas un commit.

**B — source durable :** schéma V3 neuf et explicitement sélectionné ; identités stables, huit historiques, participations rattachées à la révision Financials exacte, versions locales et intervalles `[activeFrom,activeTo)`. Reconstruction directe Pot@V, y compris état terminal ; lectures courantes et historiques groupées/sélectives ; distinction NotLoaded/Absent/Present ; faits d’autorisation et de références à la même V ; recherche exhaustive payeur OU participant dans les Expenses actives ; primitives typées de création/fermeture/insertion et métadonnée de version, sous transaction appelante. Les anciennes valeurs sont conservées. Anomalies et incohérences sont signalées, jamais réparées par un fallback.

## Modules, fichiers et décisions techniques

Production observée : **32 fichiers** : 25 dans `domain-pot/.../domain/pot/v3`, trois ports dans `engine-write-pot/.../port/persistence/v3`, trois classes JDBC dans `infra-persistence-primary-jpa/.../jpa/v3`, et `db/v3-pot/migration/V1__pot_components.sql`. Tests : `PotTransitionsTest`, `V3FoundationArchitectureTest` et ses neuf fixtures imbriquées, `PotHistoryPostgresTest`. Collecte : `scripts/testing/verify-v3-l1.py`, [manifeste attendu](L1_Proof_Manifest.json). L’[inventaire complet des fichiers](../testing/evidence/L1-AB/changed-files.json) donne les chemins et statuts Git.

- Le namespace V3 distinct est autorisé par le plan §15 pour les composantes incompatibles avec V2. PotId/ShareholderId/ExpenseId/UserId/Label/Name sont réutilisés ; les agrégats mutables, rationnels long, writers et projections V2 restent historiques, sans second chemin activé ni couche de compatibilité ajoutée.
- Les transitions sont dans `domain-pot`, sans Spring/JPA/SQL/transaction. Les ports sont purs ; les adaptateurs sont injectés explicitement par constructeur. Aucun nouveau module/POM, Service par commande, framework de transitions/mutations/faits ou mapper universel.
- JDBC est utilisé dans le module de persistance existant pour les lectures groupées et contrôles d’intervalles. Le [contrat de persistance](L1_B_Persistence_Contract.md) décrit le schéma, le bootstrap, les obligations transactionnelles et le passage à C.
- Les rationnels sont stockés en deux textes d’entiers canoniques, sans précision numeric/bigint/flottant. SQL protège syntaxe, signe et nullité ; le lecteur refuse les fractions non réduites. Les FK visent les identités Shareholder du même Pot ; aucune suppression en cascade.
- Les lecteurs exigent un snapshot appelant REPEATABLE_READ ou SERIALIZABLE sur la même source WRITE. Les writers ne créent ni transaction, commit, retry ou événement. Ils valident les révisions avant fermeture. Des contraintes différées refusent une version sans effet et une création incomplète.
- `writer_xid xid8`, technique et confiné à la persistance, prouve que la métadonnée candidate appartient à la transaction physique courante. Il empêche l’ajout tardif à une V déjà committée. Le test d’alias d’époque distingue des identifiants différant de 2^32. Aucun CAS/claim/fencing applicatif C n’est implémenté par ce garde.

## Périmètre déclaré et politique du réacteur

| Lot | Impact déclaré / observé | Slice primaire | Secondaire | Base métier | Global architecture | Full reactor |
|---|---|---|---|---|---|---|
| A | domain-pot ; tests domaine + architecture | COMMAND | PROJECTION | NONE | REQUIRED / exécuté | NON requis / non exécuté |
| B | ports internes + infra-persistence-primary-jpa ; tests et migration V3 | COMMAND | PROJECTION | CURRENT V3 neuf | REQUIRED / exécuté | NON requis / non exécuté |

**Franchissement inattendu : aucun.** Aucun changement de production READ/HTTP/Identity/Consumption générique, runtime/composition ou infrastructure transactionnelle. La fermeture Maven est celle des ancres et ne constitue pas un impact de production sur tous ses modules. Aucun `-amd`. Le checkpoint A/B n’est pas la clôture majeure L1 ; le full reactor reste dû à E selon le plan, sans restructuration majeure découverte ici.

## Commandes exécutées et résultats frais

Toutes les commandes Maven sont lancées depuis `app/`, via le collecteur avec comparaison des entrées avant/après et archivage des XML bruts. Les trois gates B finaux repartent de targets supprimés, avec inventaire du bytecode/ressources générés et des dépendances JAR résolues.

| Gate | SHA testé | Tests / failures / errors / skips | Méthodes obligatoires | Couverture complète du gate |
|---|---|---|---|---|---|
| a-domain | `23f18a01` | 220 / 0 / 0 / 0 | 14 PASS | PASS |
| a-consumers | `23f18a01` | 1190 / 0 / 0 / 1 | 14 PASS | INCONCLUSIVE |
| a-arch | `23f18a01` | 1422 / 0 / 0 / 1 | 19 PASS | INCONCLUSIVE |
| b-persistence | `887c24f3` | 856 / 0 / 0 / 0 | 33 PASS | PASS |
| b-consumers | `887c24f3` | 1209 / 0 / 0 / 1 | 33 PASS | INCONCLUSIVE |
| b-arch | `887c24f3` | 1441 / 0 / 0 / 1 | 38 PASS | INCONCLUSIVE |

Commandes exactes :

```bash
./mvnw -pl domain-pot -am test
./mvnw -pl infra-persistence-primary-jpa -am test
./mvnw -pl runtime-command-consumption-worker,runtime-task-consumption-worker -am test
./mvnw -pl architecture-tests -am test
```

Les commandes consommateurs et architecture ont été exécutées pour A puis B. Support : `python3 -B -m unittest discover -s scripts/testing -p test_summarize_v3.py` : 7 tests PASS. `git diff --check` a été exécuté. Les diagnostics ciblés `-Dtest=PotTransitionsTest`, `-Dtest=V3FoundationArchitectureTest` ou `-Dtest=PotHistoryPostgresTest`, avec `-Dsurefire.failIfNoSpecifiedTests=false`, sont du feedback et ne remplacent aucun gate non filtré.

Commandes de diagnostic effectivement utilisées (feedback seulement) :

```bash
./mvnw -pl architecture-tests -am test -Dtest=V3FoundationArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl infra-persistence-primary-jpa -am test -Dtest=PotTransitionsTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl infra-persistence-primary-jpa -am test -Dtest=PotHistoryPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
```

Le skip unique est `TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`, conditionné par `step3b.measure`, une mesure V2 optionnelle hors manifeste obligatoire L1. Il reste SKIP, jamais PASS. Les gates consommateurs/architecture sont BUILD SUCCESS mais leur couverture globale est INCONCLUSIVE. Le dernier G-ARCH exécute **38 méthodes obligatoires** : 14 domaine A, 19 PostgreSQL B, 5 ArchUnit, toutes présentes une fois et PASS. Les résultats de commandes répétées ne sont pas additionnés comme tests uniques.

Le feedback B initial sur `f72a9f30` a exécuté 855 tests sans défaut mais est **remplacé** pour certification par le feedback corrigé : la revue a identifié l’insuffisance d’un garde xmin/32 bits ; le code final conserve l’époque xid8 complète. Les préflights/diagnostics ayant échoué (normalisation CRLF du wrapper Windows, bytecode Python généré, syntaxe/inférence de type et prédicat ArchUnit) ont été corrigés avant les gates retenus. Leurs traces restent consultables ; aucun ancien résultat n’est effacé ou requalifié.

## Preuves PostgreSQL, oracles et archives

PostgreSQL réel **17.11**, Maven **3.9.14**, JDK **21** ; image/digest et options effectives dans chaque `environment.json`. Bootstrap métier uniquement `classpath:db/v3-pot/migration` dans des schémas aléatoires de conteneur. Aucun schéma métier obtenu par ddl-auto, aucune baseline V2 TRUSTED supposée. Les gates non filtrés exécutent aussi les suites historiques transitives PRIMARY V1–V31 et READ V1–V14 ; leurs logs sont conservés. Aucune campagne HISTORICAL séparée n’a été lancée.

Le test V1…V9 compare chaque champ/identité/enveloppe avec un oracle littéral indépendant, dans un ordre mélangé à seed 731, puis relit une ancienne V. D01-M vérifie les intervalles exacts de Header/Details/Weight après suppression. Les autres preuves couvrent rollback/no-op, bornes, absence versus non-chargement, droits/références à V, dernière référence payeur/participant parmi 35 Expenses précédentes, rationnels arbitraires/default/spécifique/décimal/zéro, corruption, contraintes, cohérence sous commit concurrent, transaction JPA/JDBC et refus des révisions tardives. Les lectures complètes observées utilisent **12 requêtes pour 1 comme pour 21 Expenses**, dont une requête de participations : preuve bornée sans SLO.

Le manifeste associe les méthodes aux lots. L1A-01…14 correspondent aux méthodes `l1a01`…`l1a14` ; L1A-15 aux cinq méthodes ArchUnit. B : b01 catalogue/restart ; b02 V1…V9 ; b03 bornes ; b04 D01-M ; b05 rollback/no-op ; b06 sélectivité/histoire ; b07 usage exhaustif ; b08 rationnels ; b09 corruption ; b10 contraintes/FK ; b11 snapshot concurrent ; b12 requêtes groupées ; b13 transaction/isolation ; b14 JPA ; b15 absence de faux fait négatif ; b16 rollback dernière création ; b17 contraintes différées/SQLSTATE 23514 ; b18 révision tardive ; b19 époque xid8. Les variantes sont des assertions tabulaires déterministes, sans paramètres JUnit cachés ou tests désactivés.

- [Index des gates](../testing/evidence/L1-AB/Gate-index.json) : commandes, SHAs, compteurs, verdicts globaux/obligatoires, exécutions retenues/remplacées.
- [Archive A brute](../testing/evidence/L1-AB/A-raw.tar.gz) et [SHA-256 A](../testing/evidence/L1-AB/A-raw.sha256).
- [Archive B brute](../testing/evidence/L1-AB/B-raw.tar.gz) et [SHA-256 B](../testing/evidence/L1-AB/B-raw.sha256).
- [Diagnostics B](../testing/evidence/L1-AB/B-diagnostics.tar.gz), [populations architecture](../testing/evidence/L1-AB/Architecture-populations.json), [inventaire fichiers](../testing/evidence/L1-AB/changed-files.json).

Extraire les archives avec `tar -xzf <archive>`. Chaque répertoire de gate contient `maven.log`, XML frais, `summary.json`, manifeste attendu, entrées Git et contrôles avant/après, environnement/dépendances. B inclut `app/infra-persistence-primary-jpa/target/v3-pot-evidence/` : catalogue, fonctions/triggers, snapshots/intervalles, références, requêtes et témoins physiques. Les artefacts sont committés hors de target ; l’audit ne dépend pas d’un target local conservé.

## A-01, A-02, I-01 et limites

**A-01 : clôture technique démontrée, audit indépendant en attente.** Le garde couvre ports de premier niveau et ports imbriqués, y compris génériques, annotations et héritage ; neuf fixtures imbriquées, huit violations évaluées individuellement. L’inventaire final du bytecode contient 17 classes de ports de premier niveau et 74 imbriquées, dont les contrats B. Les exceptions V2 sont nominatives dans `LEGACY_PORT_CONTRACTS`, sans autorisation générale d’engine ; échéances : contextes Pot lors de C, discovery/outcomes lors du lot Command/Consumption, ordering lors de Projection/READ, admission lors de Supra WRITE.

**A-02 : amorcé et contrôlé au checkpoint, non clos pour tout L1.** Sources/manifeste/POM/migrations/configurations/scripts comparés à Git avant/après ; targets frais B, entrées générées/JARs/configurations externes inventoriées. Le contrôle automatique H→publication autorise seulement le rapport, le statut du canon et les artefacts explicitement listés ; il vérifie aussi la tête distante. Ses résultats de publication sont ajoutés au dossier documentaire. C/D/E et leur clôture complète restent dus avant clôture L1 ; aucun simple fingerprint ne vaut clôture.

**I-01 : inventaire A/B complété, suivi transversal ouvert.** Le réemploi/remplacement V2 est documenté ci-dessus et dans le contrat ; l’exhaustivité des futurs writers C, projections READ et migrations suivantes reste à établir à leur entrée.

Pas de certification globale DOM/EVT/CON/PRJ/SEC, ni API/Bruno/k6/SLO, ni balances complètes D, ni disponibilité READ, ni protocole d’invitation/Identity. A prouve le noyau pur ; B prouve schéma/lectures/primitives sur PostgreSQL. C doit encore protéger les faits jusqu’au commit, arbitrer CAS/claims, reprendre complètement et rendre demande/résultat/événements/slot atomiques. L’instant stocké est fourni par l’application, pas une heure physique de commit certifiée. Les limites de ressources et la charge ne sont pas certifiées.

## Publication, worktrees et prochain événement

Travail dans `/private/tmp/pocoma-v3-l1-ab`, détaché de la référence approuvée, pour préserver le checkout initial `feat/runtime-monolith` à `90a3e9bc` et le worktree `feat/v3-architecture` à `ddca2827` avec ses changements documentaires indexés. Leurs HEADs et listes de modifications ont été revérifiés avant publication, identiques à l’état initial observé. Ils ne sont ni nettoyés, ni stashés, ni déplacés. Seule la référence distante autorisée est avancée sans force ; la branche locale de l’autre worktree reste à son état préexistant. Aucun fichier préexistant n’a été ajouté à nos commits.

Tête distante revérifiée avant publication : `8221692287420cd69feebd170df7c9ea6c279fdd`. Après push, la correspondance du commit publié avec `887c24f3f266304e46c281cc61ba170f35d4de9a` et la divergence distante sont contrôlées automatiquement et consignées. Le worktree de livraison peut conserver les extractions brutes de preuves ; aucune entrée exécutable non committée ne peut affecter les gates ou la publication.

**Prochain événement : audit indépendant A/B. STOP. Aucun passage à C sur autoévaluation.** Tout défaut bloquant de l’audit devra être corrigé et revérifié avant audit accepté et nouvelle autorisation explicite C.

## Constat de publication conservé

Le commit code + rapport + archives `0942ecb3c1c07d6baf7bab3d136270e96bfda380` a été poussé sans force sur `feat/v3-architecture`. La lecture distante a retourné exactement ce SHA ; divergence **0 / 0**. Le [contrôle automatique de publication](../testing/evidence/L1-AB/Publication.json) compare toutes ses entrées à `887c24f3f266304e46c281cc61ba170f35d4de9a` et aux trois manifestes réellement exécutés : **PASS**, digest d’entrées `8c53f5805e2c3b398095afe60297ac665d413175c52534938b928d3fc90d94a4`. La [sortie du push](../testing/evidence/L1-AB/Push.log) est conservée.

Le commit documentaire qui ajoute ce constat ne change aucune entrée exécutable ; le contrôle H→tête distante est répété après sa publication. Ce suivi ne clôt pas A-02 pour les lots L1 futurs. Le prochain événement reste l’audit indépendant A/B, sans démarrage C.
