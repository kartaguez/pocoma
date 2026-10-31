# Pocoma V3 — Contre-audit final indépendant de la provenance Maven

31 octobre 2026. **Verdict : PASS WITH RESERVATIONS.** Aucun faux PASS reproductible
n'est obtenu sur le SHA **`8ebf06a477bfb19cbe2bc57be6aae070b05945df`** dans le modèle
local retenu. L'isolation des producteurs et la complétude sont démontrées pour
FOUNDATION et FAST Identity. Le finding historique AUD-VERIFY-001-R1 peut être
considéré fermé pour ce périmètre, avec les réserves explicites ci-dessous.

**Décision : oui, FAST / CHANGE / INTEGRATION peuvent être adoptées comme procédure
opérationnelle standard pour les recettes Identity et FOUNDATION actuellement
revues, conformément aux gates de la politique.** Ce constat ne certifie pas
SPEC-10 et ne généralise pas les garanties aux autres slices ou formes JUnit.
Aucune correction n'est engagée.

## 1. Références, indépendance et périmètre

Dépôt `kartaguez/pocoma`, branche `feat/v3-architecture`. Le HEAD local et le HEAD
distant valent tous deux `8ebf06a4` avant l'audit ; un fetch réel confirme encore
une divergence **0/0** avant préparation de la publication. Le worktree initial
est propre. Les tests s'exécutent dans un **clone partagé détaché**, initialement
sans `target`, sous `/private/tmp/pocoma-final-provenance-audit/checkout`.
Les dépendances Maven sont chaudes ; les classes des deux nominaux sont compilées
dans ce nouveau checkout. Environnement : macOS arm64, Temurin 21, Maven 3.9.14,
Surefire/Failsafe 3.5.5, Docker 29.9.2. Les accès Docker/réseau nécessaires ont été
accordés aux commandes ; le premier contrôle distant sous sandbox avait échoué
sur la résolution DNS, avant sa répétition réussie.

Références consultées directement :

- [Contre-audit publié à b8df79a2](Verification_AUD_VERIFY_001_Independent_Counter_Audit.md),
  son pilote, son contre-exemple CLI et son dossier de preuves. Ce commit est
  documentaire : le SHA d'implémentation audité par ce précédent rapport est
  **6d40ed17**, et son faux PASS est bien historique.
- [Résolution publiée à 8ebf06a4](Verification_AUD_VERIFY_001_Provenance_Resolution.md),
  README, summary, manifeste et archive ; **diff exact `b8df79a2..8ebf06a4`**.
- CLI, lancement Maven, registre, collecte/validation, POM parent et configurations
  Surefire des modules, tests rapides et scénarios T1 à T10.
- [Politique normative Reactor](../testing/Reactor_Verification_Policy.md), notamment
  FOUNDATION, §35 et les conditions des gates ; [SPEC-10](SPEC-10_Test_Certification.md),
  distinction entre contribution bornée et certification.

Les **4 fichiers indexés et 124 membres** du dossier de contre-audit, puis les
**9 fichiers indexés et 2 224 membres** de la résolution sont vérifiés en taille,
chemin et SHA-256 avant exploitation. Aucun membre manquant, inattendu ou dupliqué.
Les manifestes sont exclus de leur propre hash et identifiés par leur commit.
Les neuf hashes de résolution comprennent le code livré : ils correspondent
exactement au checkout audité. Les dix fichiers du diff sont :

| Fichiers | Examen |
|---|---|
| `app/pom.xml` | Profil et héritage des producteurs |
| `scripts/testing/verify.py` | Lancement, verrou, découverte, collecte et verdict |
| `scripts/testing/test_verification.py` | Régressions rapides, exécutées à nouveau |
| `scripts/testing/test_verification_cli.py` | T1–T10, injection par wrapper dans un clone, limites de ces tests |
| `docs/testing/Reactor_Verification_Policy.md` | Delta normatif descriptif §35 |
| `docs/v3/Verification_AUD_VERIFY_001_Provenance_Resolution.md` | Conclusions et limites annoncées |
| README, summary, manifest et archive sous `verification-provenance-resolution-b8df79a2/` | Intégrité complète et relecture des reçus/XML pertinents |

**Déclaration préalable :** slice primaire **FOUNDATION canonique** ; contribution
secondaire **FAST Identity de COMMAND**, contrôles ciblés de l'outillage et des
producteurs. Impact productif autorisé/observé : **NONE**. Aucune modification de
code, POM, configuration, launcher, test, oracle ou script dans le checkout audité.
Franchissement inattendu : **NONE**. Architecture globale **NOT REQUIRED / NOT RUN** ;
full reactor **NOT REQUIRED / NOT RUN**, car aucun changement architectural ni gate
globale de livraison applicative. DB : fixtures FOUNDATION courantes uniquement ;
aucune campagne supplémentaire de migrations historiques. CHANGE/INTEGRATION
Identity, I5 et certification SPEC-10 **NOT RUN**, sans finding nécessitant leur rejeu.

## 2. Isolation effective et chaîne de validation

Le [profil parent, lignes 119–158](../../app/pom.xml#L119) est activé par la propriété
`pocoma.verification.id`. Il configure **le producteur**, avant exécution :

```text
<output>/reports/<artifactId>/surefire-reports
<output>/reports/<artifactId>/failsafe-reports
```

Failsafe reçoit aussi son `summaryFile` isolé. L'ID, l'artifactId et le producteur
sont transmis aux JVM et enregistrés dans les XML. Les deux propriétés de commande
sont ajoutées **avant** `help:effective-pom` et avant `test`. Le modèle effectif
contrôle chemin et propriétés au niveau plugin **et** de l'exécution Surefire
([verify.py:183](../../scripts/testing/verify.py#L183)). Une configuration inconnue
est bloquante. Une présence de plugin Failsafe dans les plugins de build est
refusée par cette CLI, qui exécute actuellement `test` ; elle n'est pas présentée
comme une preuve d'un futur lifecycle Failsafe.

Le dossier de campagne et les dossiers des producteurs doivent être nouveaux
([verify.py:515](../../scripts/testing/verify.py#L515)). UUID et chemin diffèrent
entre campagnes. La collecte utilise uniquement les dossiers autorisés du modèle ;
les anciens `target/*-reports` sont inventoriés à titre diagnostic. Les liens
symboliques et fichiers à plusieurs liens physiques sont refusés. Les octets lus
une seule fois pour chaque XML sont ceux archivés et évalués
([verify.py:553](../../scripts/testing/verify.py#L553)).

Le validateur exige exactement une propriété de chaque type, avec les valeurs de
l'invocation, du chemin et du producteur ; il relie aussi le nom de suite au module
source découvert ([verify.py:434](../../scripts/testing/verify.py#L434)). La seule
cohérence chemin/marqueur ne suffit donc pas. Puis il compare les identités avec
multiplicités, vérifie compteurs, absence de skips et erreurs, et impose un code
Maven nul ([verify.py:451](../../scripts/testing/verify.py#L451)).

**Observation directe indépendante :** sur FAST Identity, la CLI est suspendue
par SIGSTOP après le lancement réel de Maven. Maven continue seul et produit les
neuf XML dans l'espace isolé, avant que la CLI puisse collecter quoi que ce soit.
Les chemins observés, propriétés des JVM et modèle effectif concordent. Ce n'est
pas un répertoire alimenté seulement au moment de l'archivage.

FOUNDATION a deux modules Java dans son modèle, `port-transaction` et
`infra-tx-spring` ; seul le second produit ses six XML. FAST Identity a dix modules
Java dans son modèle et trois producteurs : `domain-pot` (21 tests),
`domain-user-identity` (31), `engine-consume-registration` (13). Les autres modules
n'ont pas de rapports obligatoires dans cette sélection filtrée.

Deux sondes natives complémentaires, sans changement du dépôt, bornent P1 :

- Surefire sur les **65 mêmes tests FAST**, avec `forkCount=2`, `reuseForks=false` :
  **neuf commandes JVM distinctes**, neuf XML, même ID de campagne et attributions
  correctes dans les trois modules ; code Maven **0**.
- `test-compile failsafe:integration-test failsafe:verify`, sur les **quatre H2**
  FOUNDATION seulement : un XML et le résumé Failsafe sont réellement produits dans
  le chemin isolé ; quatre succès, zéro failure/error/skip, code Maven **0**.
  Ce résultat est une sonde du producteur, pas un nouveau verdict de la CLI.

Le dossier publié de l'auteur démontre également six forks Surefire FOUNDATION
et Failsafe multi-modules sur 65 tests. Ces pièces ont été rehashées et examinées ;
elles restent des observations archivées, distinctes de nos exécutions fraîches.

## 3. Contre-exemples indépendants contre la CLI réelle

Les pilotes sont nouveaux et **n'importent pas le sélecteur pour piloter ses
verdicts**, ne le monkeypatchent pas et ne remplacent ni `mvnw` ni Java. Ils lancent
la CLI originale par sous-processus. Pour les substitutions après succès Maven,
SIGSTOP suspend le parent pendant que son vrai Maven termine ; le pilote manipule
les XML puis reprend le parent par SIGCONT. Le statut du vrai processus Maven
reste celui lu par la CLI. Les altérations sont confinées aux preuves/`target` du
clone jetable ; les fichiers suivis restent identiques au SHA audité.

Base `B=/private/tmp/pocoma-final-provenance-audit`, `E=$B/evidence`. Toutes les
commandes CLI du tableau sont exécutées depuis `$B/checkout`, sous la forme :

```sh
python3 -B scripts/testing/verify.py <niveau> <scope> --output "$E/<dossier>"
```

Le fichier `independent-cli-records.json` conserve pour **chaque lancement** l'argv
complet, la préparation, chemins, empreintes/transferts, code de sortie et reçu
observé. Les logs console, logs Maven, modèles, plans et XML conservés complètent
ce journal. Le tableau indique le **nouveau verdict**, pas le PASS ancien utilisé
comme matériau d'attaque.

| Dossier / arguments | Préparation exacte et observation | Code CLI ; verdict ; cause |
|---|---|---|
| **S1-legacy-old-mtime** / `CHANGE FOUNDATION` | Six XML historiques valides dans `app/infra-tx-spring/target/surefire-reports`, mtime fixé à 1. Après succès réel de Maven, suppression des cinq XML courants PostgreSQL ; quatre H2 courants conservés. | **1 ; INCONCLUSIVE**, 4 comptés, **24 obligations absentes**. Les historiques n'entrent pas dans la collecte. |
| **S1-legacy-renewed-mtime** / `CHANGE FOUNDATION` | Même préparation, mais `copyfile` et `utime` renouvellent le mtime historique. | **1 ; INCONCLUSIVE**, 4 comptés et les mêmes 24 obligations absentes. Les dates renouvelées ne changent rien. |
| **S2-previous-canonical-into-fast** / `FAST foundation` | Première invocation : nominal FOUNDATION 28. Seconde : quatre H2 réels. Ses XML sont remplacés/complétés par les six XML inchangés de la première, dans le nouveau dossier producteur ; mtime renouvelé. | **1 ; INCONCLUSIVE**, **0** compté ; `Provenance mismatch: pocoma.verification.id`. Les deux scopes, IDs et chemins diffèrent. |
| **S3-owner** / `FAST foundation`, **S3-contender** / `FAST identity` | Suspension du propriétaire alors que son Maven est actif ; lancement simultané d'une seconde CLI dans le même checkout. | Contendant : **2**, refus `Another verification owns app targets`, aucun dossier créé, aucun nouveau verdict. Propriétaire repris : **0 ; PASS 4/4**. Le verrou est effectif. |
| **S4-cli-terminated-partial** / `CHANGE FOUNDATION` | Après le premier XML complet, **9 cas**, SIGTERM à la CLI pendant que Maven/fork sont actifs ; les six anciens XML partagés sont toujours présents. | **2 ; FAIL**, diagnostic signal 15 ; groupes Maven/fork absents après terminaison, verrou libéré. Le reçu d'interruption compte 0, sans prétendre qu'aucun test n'a commencé ; le XML partiel est conservé. |
| **S5-maven-killed-partial** / `CHANGE FOUNDATION` | Même présence historique. Après un XML complet de 9 cas, SIGKILL au groupe Maven ; la CLI n'est pas interrompue. | **1 ; FAIL**, **9** cas comptés, Maven **−9**, 19 obligations absentes et diagnostic explicite d'échec du processus. |
| **S6-foreign-source-module** / `FAST identity` | Déplacement du XML `ShareholderIdentityCollisionTest` courant de Pot vers le dossier Identity. Seule la propriété module est alignée sur le nouveau chemin ; ID courant et cas restent inchangés. | **1 ; INCONCLUSIVE**, 64 cas acceptés ; `report attributed to wrong source module`. Le refus ne repose pas sur un ancien ID ni sur un simple désaccord chemin/marqueur. |
| **S7-directory-symlink** / `FAST foundation` | Remplacement du répertoire producteur par un lien symbolique vers celui du précédent nominal. | **2 ; INCONCLUSIVE**, `producer directory replaced or absent`, avant évaluation des XML. |
| **S8-hardlink-current** / `FAST foundation` | XML courant remplacé par un lien physique vers ses propres octets, `st_nlink=2`. | **2 ; INCONCLUSIVE**, `unauthorized report location/link`. |
| **S9-existing-output** / `FAST foundation` | `--output` désigne le dossier du nominal FOUNDATION déjà existant. | **2**, refus `File exists`, aucun nouveau verdict ni lancement Maven. Le hash de l'ancien `result.json` PASS reste inchangé. |
| **S10-current-incomplete** / `FAST foundation` | Suppression d'un testcase courant et ajustement cohérent du compteur XML. | **1 ; INCONCLUSIVE**, 3 cas et une identité obligatoire absente. |
| **S11-equal-total-duplicate** / `FAST foundation` | Remplacement de la seconde identité par un doublon de la première ; total maintenu à 4, provenance courante intacte. | **1 ; INCONCLUSIVE**, 4 cas mais identité absente et `Duplicate method execution`. |
| **S12-no-reports** / `FAST foundation` | Suppression de tous les XML courants après succès réel Maven, anciens XML complets toujours présents dans target. | **1 ; INCONCLUSIVE**, 0 cas ; obligations absentes. |
| **S13-exact-hidden-filter-prepared** / `CHANGE FOUNDATION` | Vrai dossier externe `.mvn/jvm.config` avec le filtre H2 original ; environnement `MAVEN_BASEDIR=$B/external-maven-base`. | **2**, `MAVEN_BASEDIR is set`, avant Maven et avant création de campagne. |
| **S14-uncatchable-owner** / `FAST foundation`, puis **S14-after-kill-contender** / `FAST foundation` | SIGKILL de la CLI propriétaire pendant son Maven. Tentative immédiate d'une autre CLI. | Propriétaire : **−9**, aucun verdict ; verrou conservé. Contendant : **2**, refus avant création de campagne. Groupe Maven arrêté puis absence de processus vérifiée avant retrait manuel du verrou. |

**Portée exacte de S1 :** Maven avait réellement produit 28 résultats, dont le
pilote retire ensuite 24 XML. Cette sonde prouve la non-contribution historique
et la complétude de collecte ; elle ne prétend pas que Maven n'a exécuté que quatre
tests. Les productions réellement interrompues et partielles sont S4/S5.
Le rejeu du canal original est refusé au préflight ; pour passer au-delà, les
scénarios d'injection ci-dessus attaquent directement les frontières restantes.

S9 a arrêté une première version du pilote : celui-ci lisait l'ancien reçu PASS
malgré le code 2 du nouveau lancement. La cause a été identifiée et la reprise a
porté seulement sur les sondes restantes. Le log initial, la version effectivement
exécutée et la reprise sont conservés. Le **PASS préexistant n'est pas un faux PASS
de la nouvelle CLI** : elle refuse ce dossier avant exécution et ne l'écrase pas.
Les consommateurs doivent vérifier le succès de la commande et son propre reçu.
Un premier S13 sans dossier externe préparé est également conservé ; la ligne du
tableau correspond au rejeu avec le vrai contenu original présent sur disque.

## 4. Résultats nominaux, identités et preuves

| Campagne fraîche | Résultat | XML / JVM | Processus Maven ; découverte |
|---|---|---|---|
| `CHANGE FOUNDATION --output …/nominal-foundation` | **PASS, 28/28**, 0 failure/error/skip, `issues=[]`, code CLI/Maven 0 | 6 / 1 | 13,190 s ; 1,224 s |
| `FAST identity --output …/nominal-fast-identity` | **PASS, 65/65**, 0 failure/error/skip, `issues=[]`, code CLI/Maven 0 | 9 / 3 | 3,934 s ; 1,282 s |

Le nominal FAST comporte la courte suspension d'observation ; ces durées ne sont
pas un benchmark de performance. Aucun `clean`/`install` implicite. Les annonces
Compiler sont de 18 puis 293 entrées sources recompilées, sans en déduire un
nombre de classes produites ou de fichiers réellement réemployés.

`verify-results.py.txt` est un lecteur indépendant : il **n'importe ni `verify.py`
ni le lecteur de l'auteur**. Il reconstitue les identités depuis les sources
actuelles et le registre pour les formes présentes, compare leurs multiplicités
aux plans et XML, relit les propriétés et modèles effectifs, vérifie chaque
archive brute contre les hashes du reçu, puis compare aux résumés Maven réels.
Les résultats portent sur **(module, FQCN, invocation)**. Les deux invocations
`linkRejectsAlreadyBoundTargetEvenToSameUser(boolean)[1]` et `[2]` sont distinctes.
Les totaux seuls ne constituent jamais la preuve.

Les **30 régressions rapides sont réexécutées : OK**. Les T1–T10 livrés sont lus,
et leurs reçus finaux archivés sont contrôlés ; ils ne sont pas intégralement
relancés, car les sondes indépendantes couvrent directement la menace demandée.
Leur T5 de statut non nul utilise un wrapper ; notre S5 interrompt le véritable
processus Maven. La relecture de leur T9 retrouve six JVM distinctes et 28 identités.

Le contre-exemple historique est reconstitué depuis ses octets publiés : **Maven 4,
XML 28, PASS**, au SHA historique 6d40ed17. Cela ne constitue pas un résultat du
SHA audité ici. Les reçus de résolution FOUNDATION 28, FAST 65, CHANGE 127 et
INTEGRATION 162 sont également rehashés et comparés aux identités sources ; leurs
XML, modules, modèles et résumés Maven concordent. Les deux derniers sont des
**observations archivées**, pas des campagnes fraîches de ce contre-audit.

## 5. Bilan P1 à P6 et findings

| Propriété | Conclusion dans le périmètre audité |
|---|---|
| **P1 — producteurs isolés** | **Démontrée** : modèle effectif, écriture observée avant collecte, Surefire multi-modules/multi-forks et sonde directe Failsafe. |
| **P2 — aucun réemploi historique** | **Démontrée pour les chemins et copies testés** : anciens chemins ignorés avec ancien/nouveau mtime ; anciens XML dans le nouvel espace rejetés par l'ID. |
| **P3 — séparation** | **Démontrée** : successions à scopes différents ; refus atomique du contendant et maintien du verrou après SIGKILL. |
| **P4 — provenance** | **Démontrée dans le modèle local retenu** : ID/module/producteur, attribution source, nouveau dossier, contrôles de liens ; aucune collision observée parmi les campagnes créées. |
| **P5 — défaillance/interruption** | **Démontrée pour les injections exécutées** : signal CLI, Maven −9 avec neuf résultats, disparition partielle/totale des rapports malgré succès du producteur ; jamais PASS. |
| **P6 — complétude** | **Démontrée sur les formes actuelles 28/65** : sources, identités et multiplicités exactes ; substitutions à total égal refusées. |

**Aucun nouveau finding MAJOR ou autre rupture d'invariant obligatoire n'est
reproduit sur ces campagnes.** Le finding historique ne dépendait que d'une copie
d'octets avec date renouvelée. Ce chemin est fermé : changer le mtime ne change
pas l'ID contenu dans les octets. Le défaut d'attribution module relevé au précédent
contre-audit est également traité et directement éprouvé par S6.

## 6. Réserves et limites non vérifiées

| Limite | Impact et décision |
|---|---|
| **Inventaire JUnit restreint** | La forme qualifiée `@org.junit.jupiter.api.Test` peut encore être omise lorsqu'elle accompagne un `@Test` simple : sonde source reproduite et conservée. Elle n'apparaît dans aucune des classes 28/65. Pas de faux PASS actuel démontré ; **réserve de généricité**, revue nécessaire avant introduction de cette forme. Ne pas promettre un refus universel de toutes les formes inconnues. Providers, héritages complexes, tests dynamiques/générés ne sont pas certifiés par cet audit. |
| **Failsafe** | Producteur H2 directement vérifié et pièces multi-modules archivées contrôlées. La CLI `test` ne certifie pas un lifecycle Failsafe lié ; **pas d'impact actuel**, revue de découverte/lifecycle obligatoire avant adoption future. |
| **Autres modules/slices** | Les producteurs courants sont bornés aux quatre modules nommés plus haut ; modèle FAST contrôlé sur dix modules. CHANGE/INTEGRATION Identity 127/162 sont relus sur preuves publiées, sans nouveau lancement. Aucun autre slice canonique, profil externe ou assemblage futur validé. **Réserve de portée**, pas de correction immédiate induite. |
| **Concurrence externe** | Le verrou exclut réellement deux CLI. Il ne peut interdire à un Maven natif ou autre écrivain non coopératif de toucher les mêmes classes/targets. La discipline d'un propriétaire reste due pour ces commandes. **Limite opérationnelle documentée**, pas de faux PASS de provenance reproduit. |
| **Interruptions** | SIGTERM CLI, SIGKILL Maven et SIGKILL CLI testés ; fin des groupes inspectée. SIGHUP/SIGINT, crash système, OOM et toutes les fenêtres de signaux ne sont pas exhaustivement testés. SIGKILL laisse volontairement un verrou nécessitant inspection manuelle. **Réserve bornée**, aucune libération dangereuse observée. |
| **Écrivain malveillant et falsification** | Les marqueurs sont lisibles dans les argv/XML. Un acteur de mêmes droits qui réécrit volontairement les résultats et les marqueurs courants peut fabriquer des preuves ; UUID et SHA-256 ne sont pas une authentification d'exécution. Ce scénario sort du modèle de copie/restauration involontaire retenu. **Aucune garantie cryptographique exigée**, aucune extrapolation à un environnement hostile. |
| **Environnement et consommation des reçus** | Un seul environnement, dépendances chaudes, Docker nécessaire aux fixtures PG. Pas de seconde machine ni qualification statistique. Un ancien reçu PASS peut subsister si un `--output` existant est refusé : le code de sortie et le rattachement à la nouvelle invocation sont obligatoires. **Réserves documentées**, pas de correction immédiate requise pour les usages conformes. |

Ces limites empêchent une conclusion universelle « tout JUnit/tout Maven/tout
écrivain ». Elles n'annulent aucune preuve positive des campagnes actuellement
utilisées. Aucune d'elles ne justifie de relancer CHANGE/INTEGRATION Identity,
I5 ou le full reactor pour répondre à la question de provenance examinée.

## 7. Commandes, conservation et publication

Commandes principales réellement exécutées :

```sh
git clone --shared --no-checkout . /private/tmp/pocoma-final-provenance-audit/checkout
git -C /private/tmp/pocoma-final-provenance-audit/checkout checkout --detach 8ebf06a477bfb19cbe2bc57be6aae070b05945df
# Depuis le clone :
python3 -B scripts/testing/verify.py CHANGE FOUNDATION --output /private/tmp/pocoma-final-provenance-audit/evidence/nominal-foundation
python3 -B /private/tmp/pocoma-final-provenance-audit/audit-cli.py
python3 -B /private/tmp/pocoma-final-provenance-audit/audit-cli-resume.py
python3 -B /private/tmp/pocoma-final-provenance-audit/exact-original-channel.py
python3 -B -m unittest -v scripts/testing/test_verification.py
python3 -B /private/tmp/pocoma-final-provenance-audit/producer-probes.py
python3 -B /private/tmp/pocoma-final-provenance-audit/verify-results.py
```

Le premier pilote réalise le nominal FAST et S1–S9 ; la reprise réalise S9–S14.
Les argv Maven complets sont dans les `plan.json`, reçus et `producer-probes.json`.
La commande canonique FOUNDATION conserve `-pl infra-tx-spring -am test`, augmentée
des seules propriétés d'isolation. Les sondes directes multi-forks/Failsafe sont
explicitement distinctes des commandes CLI canoniques. Les vérifications Git
comprennent `diff b8df79a2..8ebf06a4`, `ls-remote`, `fetch`, `rev-list --left-right
--count`, `diff --check` et la vérification finale du SHA distant.

Publication strictement documentaire : **ce rapport et quatre fichiers** sous
[verification-final-provenance-audit-8ebf06a4](../testing/evidence/verification-final-provenance-audit-8ebf06a4/README.md).
Le [summary](../testing/evidence/verification-final-provenance-audit-8ebf06a4/summary.json)
indexe les résultats ; le [manifest](../testing/evidence/verification-final-provenance-audit-8ebf06a4/manifest.json)
fixe les hashes des fichiers publiés et des **1 752 membres** de l'archive autonome
(**11 277 224 octets**), intégralement restaurés et revérifiés.
Les preuves anciennes restent à leurs chemins et ne sont pas réécrites. Les
pilotes nouveaux sont des pièces documentaires `.py.txt` dans l'archive, pas des
changements sous `scripts/`. Les processus sans rapport avec l'audit sont omis des
snapshots publiés ; aucun lien actif n'est embarqué dans l'archive, leurs cibles
et attributs étant consignés comme observations. Le lecteur fonctionne également
sur une extraction neuve sans checkout original.

Le commit de publication est descendant de `8ebf06a4`, sans changement de code,
configuration ou script, sans force push. Son SHA final est communiqué après push
normal et relecture distante ; il est également retrouvable par `git log -1
--format=%H -- docs/v3/Verification_AUD_VERIFY_001_Final_Provenance_Counter_Audit.md`.

## 8. Décision opérationnelle

**PASS WITH RESERVATIONS ; adoption opérationnelle bornée validée.** Utiliser les
recettes FAST / CHANGE / INTEGRATION Identity et FOUNDATION actuellement revues,
avec leurs gates obligatoires, leurs inventaires et leurs reçus propres. Une
recette filtrée reste un feedback borné ; `CHANGE FOUNDATION` reste la gate
canonique non filtrée. Les nouveaux tests, providers, configurations, lifecycles
ou slices nécessitent leur propre revue et les preuves proportionnées dues.

**SPEC-10 : NOT RUN / aucune certification.** Aucun PASS global V3, aucune levée
de réserve I5 ou d'obligation métier, de sécurité, de reprise ou de performance.
Dans le modèle local audité, les copies historiques inchangées, y compris à mtime
renouvelé, ne contribuent plus à un nouveau PASS.
