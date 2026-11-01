# L3/I5 — Remédiation Q-ID et dossier provisoire pour audit

**1er novembre 2026 — QUALIFICATION IN PROGRESS — CLOSURE PENDING HUMAN ACCEPTANCE**

Proposition technique : **QUALIFICATION INTERNE DÉMONTRÉE AVEC RÉSERVES — AUDIT INDÉPENDANT PENDING — HUMAN ACCEPTANCE PENDING**.
Auteur de la remédiation : Codex. Audit indépendant de cette livraison **PENDING**.
L3 reste OPEN ; L4 n’est pas ouvert. Le GO humain actuel autorise la remédiation
et la publication provisoire, sans acceptation finale.

| Finding initial | Constat de l’audit transmis | Traitement factuel | État proposé à l’audit |
|---|---|---|---|
| AUD-I5-01 — BLOQUANT | Trois observations PostgreSQL Q-ID absentes | Sept demandes uniques ; trois probes directes PASS, snapshots et oracles §10.2 archivés | Remédiation technique démontrée |
| AUD-I5-02 — BLOQUANT | Clôture humaine inventée | Acte converti en projet PENDING ; ARB-I5-01 limité à la préparation ; aucun GO L4 | Rectifié, décision finale absente |
| AUD-I5-03 — MAJEUR | Preuves partielles présentées complètes | O06/I/P distinguent historique partiel et preuves Q-ID fraîches ; O20/O21/O25 non démontrées | Rectifié ; réserves conservées |
| AUD-I5-04 — MAJEUR | Gates/campagnes mal attribuées | Six campagnes historiques récupérées et vérifiées ; SHA, état dirty, argv, modèles, inventaires et limites archivés ; autres assertions UNPROVEN | Traité avec réserve de bruts non retrouvés |
| AUD-I5-05 — MAJEUR | Manifeste non autoporteur | Matrices normatives restaurées, XML/membres/lignes/reçus/tailles/hashes indexés ; 64 reçus I4 référencés sans duplication | Dossier vérifiable soumis à audit, sans autocertification |

Le contre-audit I5 initial « NOT ACCEPTED — clôture prématurée » et ses cinq
findings sont transmis par le mandat humain ; aucun original distinct n’a été
fourni dans le dépôt. Le présent auteur ne se déclare pas auditeur indépendant.

## Provenance et rectification de l’histoire

Le dossier Gemini historique demeure récupérable, aux octets d’origine, par
`git show 0ba18ec1e2fb9d9ead4e6ff84b9c839048c82dc3:docs/v3/L3_Identity_Closure.md` et les trois autres chemins
indexés dans [Historical_Rectification.json](../testing/evidence/L3-I5/Historical_Rectification.json).
Ses affirmations de clôture, d’acceptation et de GO L4 sont **supplantées** par
cette rectification ; elles ne constituent aucune décision humaine effective.
Les acquis I1–I4 et leurs décisions restent inchangés.

| Référence | Constat vérifié |
|---|---|
| Branche / HEAD local et distant réel au préflight | `feat/v3-architecture` / `0ba18ec1e2fb9d9ead4e6ff84b9c839048c82dc3` |
| SHA long fourni au mandat | `0ba18ec188d40fe93eec688842c1611ce0c6d595` absent ; le préfixe unique `0ba18ec1` identifie le dossier réel ci-dessus |
| Code I4 testé | `145f42731a4e35488e9aaa56a7de3c3f453a9231` |
| Publication contre-audit L2/I5 | `32504fca0f8d5ba6f236cf3111145d346ddc7930` |
| Rectification L2/I5 réelle | `028930e09c680823dc2ee1f24da3e48df1d0ceae` ; le SHA long `028930e071…` du dossier Gemini est erroné |
| Q-ID code / outils DEV exécutés | `0ba18ec1e2fb9d9ead4e6ff84b9c839048c82dc3` ; jar et source opérateur SHA-256 dans le manifeste |
| Travail initial et worktrees | Checkout principal et quatre worktrees détachés propres ; aucun reset/stash/changement des autres worktrees |

La comparaison [Input_Equivalence.json](../testing/evidence/L3-I5/Input_Equivalence.json)
couvre toutes les entrées suivies `app/` et `scripts/`, donc tests, helpers,
POM, ressources, migrations, configurations et harnais. Depuis I4, **trois
fichiers de tests temporels et trois entrées de vérification changent** ; production,
POM, ressources, migrations et `scripts/v3/` sont identiques. Aucune équivalence
globale d’exécutables I4→courant n’est prétendue. Les sources spécifiques Identity
et leurs assertions restent attribuées à E4 ; les recettes Gemini récupérées
vérifient aussi les 1 265 entrées `app/` courantes et leurs SQL/contrôles.

## Portée déclarée et commandes effectivement exécutées

Impact productif attendu/observé **NONE**. Primaire **COMMAND**, secondaires
canoniques **NONE** ; Monolithe = racine historique réemployée, pas nouvelle slice.
Risques : Identity, autorisation, atomicité/publication, runtime et provenance.
DB **CURRENT V3 neuve**, WRITE V1–V6 / READ technique V1 ; baseline TRUSTED NONE.
Franchissement inattendu **NONE**. Aucune source/test/POM/migration/grant/outil
existant sous `app/` ou `scripts/` modifié.

Architecture reste **REQUIRED** pour O20 ; sa gate I4 est **RUN / FAIL**.
Aucune nouvelle gate globale n’est requise par cette remédiation sans frontière
modifiée (§§17/34, ARB-I5-01). Full reactor **NOT REQUIRED / NOT RUN** : ni Wave
majeure, ni main/release, ni restructuration ; CERTIFICATION globale **NOT RUN**.
Les gates non acquises ne sont pas effacées par cette déclaration.

Commandes exécutées dans cette intervention :

```sh
# Depuis app/ : fabrication seulement, aucun PASS de test produit.
./mvnw -pl runtime-command-consumption-worker -am clean package -DskipTests
# Depuis la racine : intégrité historique seulement, aucune exécution de tests.
python3 -B docs/testing/evidence/L3-I4/verify_evidence.py
python3 -B docs/testing/evidence/L3-I5/verify_dossier.py --self-test
git diff --check
# Campagne opérateur locale, source et argv exacts archivés :
python3 -B /private/tmp/pocoma-l3-i5-remediation-private/run_qid.py
```

Le build est BUILD SUCCESS (11,088 s). L’opérateur appelle les outils existants
`worker-dev.py prepare`, `inject-identity`, `identity-token`, `verify-identity`,
`stop`, le jar à chemin absolu et `psql -X -v ON_ERROR_STOP=1` via Docker.
Tous les argv, horodatages, PID et codes sont dans `Q-ID-public.tar.gz:commands.json`,
`prepare-commands.json` et `initial-preflight-commands.json`. Les fichiers secrets
n’y sont pas inclus. Les commandes Git/lectures et contrôles d’intégrité documentaires
accompagnent ces opérations ; aucune suite Maven `test` n’a été relancée.

Deux diagnostics opérateur sont conservés, sans répétition de stimulus : refus
d’allowlist avant tout DDL (adresse JDBC exacte avec `/32`, correction privée),
puis refus du parseur local de JSON PostgreSQL multiligne après préparation,
avant tout démarrage/demande (décodage JSON complet corrigé). La préparation réussie
est conservée et **jamais répétée**. Aucun timeout, oracle SQL normatif, assertion
applicative ou runtime n’est changé. Les sources de chaque version opérateur et
les diagnostics sont archivés. Une seule séquence R1–R7 a été soumise.

## Q-ID : observations fraîches nominatives

Invocation `46873469f44042c5b43d0a0a30b10481`, du `2026-11-01T08:50:24.107249Z` au
`2026-11-01T08:50:51.347783Z`. PostgreSQL : `PostgreSQL 17.11 on aarch64-unknown-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit	postgres	127.0.0.1	5432`.
Image épinglée `postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73`.
Worker réel Identity seule, owner JPA livré, M_W=4, quatre partitions, pools
Business=6 / Control=10 pour WRITE et READ ; configuration non secrète dans le reçu.
Versions Java/Maven/Docker observées : Temurin 21+35, Maven 3.9.14, Docker 29.9.2.

| Ordre | Demande exacte | Intention | Résultat terminal |
|---|---|---|---|
| 1 / R1 | `e1908899-4490-5c3f-8a7f-f6ced2c575a9` | REGISTRATION A→A | CHANGED / v1 |
| 2 / R2 | `a094a35b-41b4-5685-a4d5-0882f27b7db4` | LINK A→B | CHANGED / v1 |
| 3 / R3 | `fa9a0f4a-63cc-5db6-b191-696611ee3645` | UNLINK A→B | CHANGED / v2 |
| 4 / R4 | `81efbb20-0008-5e17-9575-65d8ce8e87ce` | REGISTRATION C→C | CHANGED / v1 |
| 5 / Q-UFREE | `969516b8-fe16-55de-8994-339dba762df5` | UNLINK A→B | REJECTED / TARGET_NOT_BOUND |
| 6 / Q-UOTHER | `8940ec60-dc2b-5c48-9650-431eb3706994` | UNLINK A→C | REJECTED / TARGET_BOUND_TO_ANOTHER_USER |
| 7 / Q-REGNEW | `3ddf3b5e-879f-5a0d-8963-62c8fc5838b8` | REGISTRATION A→A | REJECTED / TARGET_ALREADY_BOUND |

Les préconditions sont lues avant injection : A liée v1 à UA avec son token
courant ; B libre v2 ; C liée v1 à UC≠UA ; R1 déjà TERMINAL/CHANGED avant R7,
nouvel UUID pour R7. Admission CONTROLLED_INTERNAL par l’injecteur autorisé.
Les tokens ne sont extraits que dans le répertoire privé 0700, fichiers 0600.

| Observation | Avant → après PostgreSQL | Issue / invariant |
|---|---|---|
| Q-UFREE | Users 2→2, ancres 3→3, histoire 4→4, mutations 6→6 ; compteurs IDENTITY 6→6, COMPLETION 4→5 | TARGET_NOT_BOUND ; quatre empreintes exactes égales, lignes métier privées égales, résultat cible/version nulls, une complétion et sa position, zéro slot, densité : PASS |
| Q-UOTHER | Users 2→2, ancres 3→3, histoire 4→4, mutations 6→6 ; compteurs IDENTITY 6→6, COMPLETION 5→6 | TARGET_BOUND_TO_ANOTHER_USER ; quatre empreintes exactes égales, lignes métier privées égales, résultat cible/version nulls, une complétion et sa position, zéro slot, densité : PASS |
| Q-REGNEW | Users 2→2, ancres 3→3, histoire 4→4, mutations 6→6 ; compteurs IDENTITY 6→6, COMPLETION 6→7 | TARGET_ALREADY_BOUND ; quatre empreintes exactes égales, lignes métier privées égales, résultat cible/version nulls, une complétion et sa position, zéro slot, densité : PASS |

Les trois reçus publics contiennent les quatre empreintes MD5 de lignes calculées
par SQL normatif (complétées par égalité des lignes privées), les deux états,
les UUID et les résultats booléens. Chaque fichier/membre est aussi protégé par
SHA-256. Tokens : null/présence/SHA-256 uniquement dans l’export ; aucune valeur brute.
Les résultats R1–R4, UA/UC, versions et tokens protégés figurent dans les snapshots
avant/après ; l’égalité du vecteur protège aussi les bindings tiers et le succès initial.

Oracle cumulatif final : **11/11 booléens vrais** : 2 Users, 3 ancres, 4 versions,
7 demandes terminales, 4 CHANGED, 3 REJECTED, 6 mutations, 7 complétions,
0 slot Identity, compteurs 6/7. Deux arrêts `stop` **code 0**, callbacks physiques
confirmés (PID 55300 et 55455 ; sorties JVM 143 normales après SIGTERM).
Les lignes exactes de démarrage/callback, leurs numéros/hashes et la sortie stop
sont dans [Runtime_Lifecycle.json](../testing/evidence/L3-I5/Runtime_Lifecycle.json).
Même jar/config/base au restart, sans prepare ; sept résultats relus, **vecteur
durable strictement identique** après chaque arrêt et après redémarrage.

Le contrôle en mémoire des logs Worker et PostgreSQL ne retrouve aucune des
valeurs secrètes connues ; seules les données expurgées sont publiées. Le brut,
les intentions, la configuration, les tokens et les snapshots exacts sont privés,
indexés par empreinte dans l’archive ; ils ne sont pas indispensables pour vérifier
l’intégrité publique. Leur audit intégral requiert l’accès opérateur autorisé.

## Campagnes réutilisées et gates

Tous les résultats de cette table sont **historiques**, jamais de nouvelles
campagnes I5. Les commandes Maven développées, listes de modules et identités
JUnit, modèles effectifs, toolchains/settings/SQL et limites sont conservés dans
le manifeste et [Recovered_Campaigns.json](../testing/evidence/L3-I5/Recovered_Campaigns.json).

| Contribution | Attribution exacte | Résultat et limite |
|---|---|---|
| E4 FAST / CHANGE / INTEGRATION | SHA I4 `145f4273`, archives I4 inchangées, 10 / 50 / 51 modules | 65 / 149 / 187 PASS bornés ; source spécifique et hypothèses comparées |
| Gemini FAST / CHANGE | HEAD `0092b4d1bafe644017717865a620f0a35b68fbb5`, worktree dirty explicitement enregistré, entrées exécutées égales à l’app courante | 65 / 149 PASS, invocations et bruts récupérés ; ne pas attribuer à un SHA propre `028930e0` |
| Gemini INTEGRATION | HEAD propre réel `028930e09c680823dc2ee1f24da3e48df1d0ceae` | 187 PASS ; 51 modules ; app/7 SQL/contrôles égaux |
| Gemini FOUNDATION | Même HEAD propre réel `028930e09c68…` | 28 PASS filtrés ; 2 modules ; pas gate FOUNDATION canonique prétendue |
| X4 Architecture | SHA I4, 28 classes / 60 modules de fermeture | 163 PASS bornés avec filtre ; **pas gate Architecture globale** |
| N4-C COMMAND + Monolithe | SHA I4, invocation `ef1fd20f15364747b0bf0ef71053857d` | **FAIL 1789/1/0/1**, r12 ; 46 limites de découverte |
| N4-A Architecture non filtrée | SHA I4, invocation `a639e9be6b4f4641a139d1a703caee82` | **FAIL 1892/2/0/1**, r05/r12 ; module Architecture SKIPPED ; 67 limites |
| COMMAND seule annoncée 1777 | Rapport L2 contre-audit, pas de brut d’invocation durable attribuable retrouvé | **UNPROVEN** ici ; BUILD SUCCESS rapporté, 0/0/1 ; ne satisfait pas COMMAND + Monolithe ni PASS strict §35 |
| Architecture tardive annoncée 163 / composition isolée 10 | Dossier Gemini `0ba18ec1`, pas de brut attribuable retrouvé pour ces invocations spécifiques | **UNPROVEN** ; E4 contient déjà 163 et les 10 Worker + 7 Monolithe avec preuves propres |
| r05 / r12 après correction L2 | HEAD dirty `0092b4d1bafe…`, app exécutée hachée égale au gel `32504fca` et au courant | Deux probes ciblées PASS, bruts/IDs vérifiés ; quarantaine courante inactive confirmée ; aucun FAIL historique réécrit |

Le skip historique attribuable est
`TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`
(`step3b.measure` absent), et non le nom inventé `measureStep3bLatencyUnderLoad`.
Un BUILD SUCCESS avec skip ne constitue pas le PASS strict de la politique §35.
Les 64 reçus I4 sont ceux de `Variant_Receipts.json`, **pas** une série inventée
r01…r64, et pointent vers `campaigns/integration-final/raw.tar.gz`, jamais vers
l’archive des tests du sélecteur. Le vérificateur I4 contrôle leurs lignes exactes.
Les 686 XML I4 et les archives récupérées ont été vérifiés sans rejeu applicatif.

## Matrice finale O01–O27

Les identifiants et scénarios sont ceux du plan I5 §4 ; les réaffectations O14–O20
du manifeste Gemini sont annulées. Statuts complets normatifs dans chaque ligne ;
preuves XML/méthodes/membres/lignes et reçus détaillés dans le manifeste JSON.

| Obligation | Scénario normatif | Statut | Disposition / preuve |
|---|---|---|---|
| O01 | Valeurs pures, transitions strictes, absence de Binding autonome/version globale | **SATISFIED** | Indexer, sans rejeu |
| O02 | Histoire exacte, ID stable, ancre zéro distincte de v1 | **SATISFIED** | Réemployer |
| O03 | Tri avant INSERT/verrou, Unicode opaque, connu/inconnu/mixte/self | **SATISFIED** | Réemployer |
| O04 | Aléa non prédictible, unicité historique, aucune fuite par représentation | **SATISFIED** | Conserver contrat + preuves |
| O05 | Un seul gagnant, aucun User perdant, deux EUI sérialisées | **SATISFIED** | Aucune requalification générale L2 |
| O06 | Négatifs Unlink et nouvelle demande après succès, avec vecteur durable exact | **SATISFIED** | Three fresh direct WRITE observations PASS; historical domain/adjacent SQL proofs remain separately attributed |
| O07 | Demande immuable PENDING sans slot, trois opérations, terminal/résultat/complétion | **SATISFIED WITH RESERVATIONS** | Documenter frontière |
| O08 | Rollback à chaque frontière, erreur technique jamais rejet métier, poisoning JDBC/JPA | **SATISFIED WITH RESERVATIONS** | Réemployer avec R1 |
| O09 | ACK perdu après vrai commit / avant commit, terminal retry sans nouvel effet, observation sans réparation | **SATISFIED WITH RESERVATIONS** | Attribuer séparément les deux observations |
| O10 | Claim remplacé/expiré, horloge après verrou, transfert de partition, completion mono-usage | **SATISFIED** | Réemployer |
| O11 | Une acquisition/tentative, budget technique unique, N/Q/admin, aucune purge | **SATISFIED WITH RESERVATIONS** | Conserver limites |
| O12 | Événements minimaux, User puis EUI contigus ; rejet complétion seule ; densité/rollback | **SATISFIED WITH RESERVATIONS** | Recevoir nominativement les reports Identity L2, sans réécrire L2 |
| O13 | Contraintes avant fermeture, terminal différé read-only, zéro DML/verrou métier tardif | **SATISFIED** | Réemployer, garder analyse de suffixe |
| O14 | Burnable asynchrone ; terminal/Q non réenrôlés ; deux sources Identity, User examiné/ignoré | **SATISFIED WITH RESERVATIONS** | Réemployer et reporter L4 |
| O15 | Moindre privilège, provenance non substituable, pas de token E2 dans résultat/event, bootstrap séparé | **SATISFIED WITH RESERVATIONS** | Runbook exact, réserves explicites |
| O16 | Deux racines actives, même owner JPA, pas Legacy/fixture ni worker supplémentaire | **SATISFIED WITH RESERVATIONS** | Pas de création de classe I5 redondante |
| O17 | Autorité réelle et témoins positifs/négatifs stables, token tiers, collision après vraie transition | **SATISFIED WITH RESERVATIONS** | Réemployer |
| O18 | Registration → vraie Command, locks jusqu'à résolution, rollback/UNKNOWN ; User abandonné reste sans droits récupérables | **SATISFIED WITH RESERVATIONS** | Réemployer, pas nouvelle démo équivalente |
| O19 | Négatif stable sans métier Pot, SELECT technique anti-effets conservé | **SATISFIED** | Reproduire exactement A1 dans le dossier |
| O20 | Architecture globale REQUIRED | **NOT DEMONSTRATED** | REQUIRED. N4-A remains FAIL. X4 163 PASS is filtered. Later reported 163 also filtered and not independently attributable here; no global PASS |
| O21 | Gate COMMAND + Monolithe | **NOT DEMONSTRATED** | REQUIRED COMMAND + Monolith. N4-C remains FAIL. Reported later COMMAND-only 1777 BUILD SUCCESS is UNPROVEN here, has one skip and omits the Monolith anchor |
| O22 | Full conditionnel et niveau de clôture | **SATISFIED WITH RESERVATIONS** | FULL NOT REQUIRED / NOT RUN for this bounded remediation under ARB-I5-01; future global gates retained |
| O23 | Provenance/équivalence, preuves durables, attribution méthodes/oracles | **SATISFIED WITH RESERVATIONS** | Recoverable E4 and six Gemini campaign artifacts checked and indexed; missing native 1777/late architecture/standalone composition bruts explicitly UNPROVEN; no inference from src/main alone |
| O24 | Rapport, runbook, manifeste et clôture autoporteurs | **SATISFIED WITH RESERVATIONS** | Four corrected provisional deliverables and program references available for independent audit |
| O25 | Audit indépendant et décision humaine L3 | **NOT DEMONSTRATED** | Independent audit of this frozen remediation PENDING; final human acceptance PENDING; no L4 GO |
| O26 | READ, HTTP, consentement externe, qualification serveur | **OUT OF SCOPE** | Transfert L4/L5, aucune implémentation I5 |
| O27 | Reconstruction métier, capacité/SLO, convergence/release/production | **OUT OF SCOPE** | Reports L6–L8, aucun GO production |

## Matrice finale I01-A–Q

| Variante | Contrat / oracle historique | Statut | Complément nouveau et limites |
|---|---|---|---|
| I01-A | État exact v1 lié et nouveau User ; ancre réservée ID réutilisé ; 1 User/1 état/1 résultat/2 mutations/1 complétion ; ordre User/EUI et densité | **SATISFIED** | Historique E4 seulement ; AuthN d'admission supposée valide ; aucune obligation READ ajoutée |
| I01-B | ID conservé, v1→libre v2→nouveau User v3 ; User initial persiste ; tokens différents | **SATISFIED** | Historique E4 seulement ; Pas de dépendance READ/HTTP pour WRITE |
| I01-C | Rejet TARGET_ALREADY_BOUND après victoire ; un seul User supplémentaire et version exacte, domaine inchangé | **SATISFIED** | Historique E4 seulement ; Compléter I01-P séparément, ne pas assimiler concurrence et rejeu post-succès |
| I01-D | Un gagnant, perdant rejeté ; versions 1/3 ; un seul User ; deux Engines et blocage physique | **SATISFIED** | Historique E4 seulement ; Réemploi B ; aucune réexécution |
| I01-E | U déduit d'E1 ; aucun User créé ; E2 v1 puis v3/v5 ; ID stable, nouveau token, états exacts | **SATISFIED** | Historique E4 seulement ; Consentement E2 externe non prouvé : contribution ID-01 interne seulement, ID-02 L5 |
| I01-F | Deux Links : seul owner gagnant ; Registration/Link sur inconnue/libre, les deux ordres gagnants, aucun User perdant | **SATISFIED** | Historique E4 seulement ; Admission Link contrôlée ; qualification fournisseur L5 |
| I01-G | Deux paramètres domaine réellement découverts ; re-Link strict rejeté au même User dans histoire v5 ; état/version/compteurs inchangés, complétion seule ; autre User sous course | **SATISFIED** | Historique E4 seulement ; Pas de besoin de nouveau test portant le nom I01-G |
| I01-H | Seul token E1 ; E2 libre N+1 et paire User/token nulle ; User persiste après dernière EUI ; nouvelle Registration autre User | **SATISFIED** | Historique E4 seulement ; Aucun mécanisme de récupération du User abandonné |
| I01-I | Domaine : motifs INVALID_BINDING/TARGET_NOT_BOUND/TARGET_BOUND_TO_ANOTHER_USER ; SQL : ancien token B d'E2 refusé après v5, compteurs d'états/mutations inchangés | **SATISFIED WITH RESERVATIONS** | Q-UFREE, Q-UOTHER ; Complete in the bounded WRITE scope after the three fresh probes; composed historical + operator proof. No internal WRITE obligation transferred to L5. |
| I01-J | Première transition gagne, deuxième INVALID_BINDING ; E1 liée/E2 libre, ordre canonique, attente serveur puis terminaison | **SATISFIED** | Historique E4 seulement ; Réemploi B |
| I01-K | Deux ordres de victoire ; si Unlink gagne, E2 sans version et ancre zéro ; sinon Link finit puis E1 est détachée ; protections réelles | **SATISFIED** | Historique E4 seulement ; Pas certification de disponibilité temporelle L2 |
| I01-L | `at(id,v)` égale chaque état exact 1…5, U et nulls ; B/C/D distincts ; B obsolète refusé ; raccord Pot refuse ancien/tiers, égalité du token courant par clé exacte | **SATISFIED** | Historique E4 seulement ; Pas accès public aux tokens historiques ; I01-Q distinct |
| I01-M | Ancres `(a,x)` et `(b,x)` distinctes en SQL ; UNIQUE couple exact ; UUID gagnant stable ; mauvais issuer ne résout aucun acteur | **SATISFIED** | Historique E4 seulement ; Aucune normalisation autorisée |
| I01-N | 28 combinaisons Engine, faute SQL après User, vecteur avant/après identique, demande PENDING/slot conservé, aucun compteur consommé | **SATISFIED** | Historique E4 seulement ; Réemploi B ; aucune nouvelle coupure préventive |
| I01-O | Proxy intercepte COMMIT réel, UNKNOWN→COMPLETED ; seconde préparation vide et vecteur durable identique ; H3 retrouve même source/résultat avec fichier reformaté | **SATISFIED WITH RESERVATIONS** | Historique E4 seulement ; H3 a seulement un reçu public expurgé, pas tous logs opérateur ; réserve explicite, pas défaut démontré |
| I01-P | Link séquentiel nouvel UUID après v5 rejeté ; Registration : domaine lié rejeté et course deux IDs prouvés, mais admission du second ID dans la course précède le succès | **SATISFIED WITH RESERVATIONS** | Q-REGNEW ; Complete in the bounded WRITE scope after the three fresh probes; composed historical + operator proof. No internal WRITE obligation transferred to L5. |
| I01-Q | I4 teste token ancien côté Command et absence de recours READ ; ni matérialisation CurrentBinding@V ni AuthN/contrôle HTTP du token | **OUT OF SCOPE** | Historique E4 seulement ; OUT OF SCOPE: READ CurrentBinding monotonic@version L4; own-session token access/AuthN/minVersion/cache/logs L5. No complete ID-01/ID-02 external claim. |

I01-I conserve ses preuves domaine/ancien token et ajoute Q-UFREE/Q-UOTHER.
I01-P conserve le Link séquentiel historique et ajoute Q-REGNEW Registration
post-succès, identifiant distinct. **A–P sont démontrées dans le WRITE interne**,
avec les limites composées de leurs lignes ; I01-Q reste OUT OF SCOPE L4/L5.
ID-01/ID-02 globaux et consentement externe ne sont pas certifiés.

## Réserves conservées et proposition soumise à décision

- R-L2-01: historical temporal FAILs retained; corrected r05/r12 recovered PASS at recorded dirty 0092b4d1 inputs equal current code. Experimental conditional proof only; 30s and arbitrary contention not certified.
- R-L2-02: availability/reacquisition not universally bounded; conservative UNKNOWN/DRAINING remains. Q-ID demonstrates two nominal drains only.
- R-L2-03: G1/G2 INCONCLUSIVE, G3 NOT PASSED/NOT COMPLETED; global SPEC-10 and remaining allocations unchanged.
- R-L2-04: original L2 fixtures are not real Identity evidence; I3/I4 supply their own bounded runtime contributions. READ/HTTP/Invitation/SLO remain allocated to future lots.
- R-L2-05: no production or release GO.
- I3 R1: delivered Worker/Monolith JPA only; manual DataSourceTransactionManager with prebound holder remains outside qualification.
- I3 R2: PostgreSQL admin, owners and provisioners trusted; closed DEV ACL profile, auxiliary roles certified at preparation; no continuous ACL surveillance.
- I3 R3: actual PG and Docker channels only; external audit/APM/production collectors not certified.
- I3 R4: old gate FAILs and 46/67 discovery limits unchanged; r05/r12 later corrected and inactive in current quarantine registry, with no retroactive rewriting.
- O20/O21: required unfiltered gates unacquired in attributable evidence. Reported 1777 COMMAND-only result is UNPROVEN and has one skip; X4 163 is filtered.
- H3 only public redacted receipt; independent I3 raw logs unavailable and original I4 independent audit only reported by its human decision.
- Independent audit of remediation and final human acceptance PENDING; no L4 GO.

A1 conserve uniquement le SELECT technique anti-effets de
`JdbcPotCommandStore.requireCompletionReady` sur `v3_pot_version`, sans lecture,
verrou ou mutation métier Pot. Aucun contrat Identity/L2 modifié.

Les findings documentaires sont soumis au prochain audit indépendant avec leurs
réserves. **O25 reste NOT DEMONSTRATED** : aucun auteur, date ou décision humaine
finale n’est prérempli. ARB-I5-01 autorisait la préparation, pas cette acceptation.
Proposition technique : **QUALIFICATION INTERNE DÉMONTRÉE AVEC RÉSERVES — AUDIT INDÉPENDANT PENDING — HUMAN ACCEPTANCE PENDING**. Statut de programme : **QUALIFICATION IN PROGRESS — CLOSURE PENDING HUMAN ACCEPTANCE**.

Les contrôles reproductibles sont décrits dans le [dossier de preuves](../testing/evidence/L3-I5/README.md).
L’intégrité, les liens, le diff et l’absence de delta productif sont contrôlés
avant publication ; le SHA publié et la concordance distante sont fournis après
commit/push. Aucun succès de publication ou avis d’audit n’est anticipé ici.
