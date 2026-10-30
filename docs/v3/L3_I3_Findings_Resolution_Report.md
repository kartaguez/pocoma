# L3/I3 — Résolution des findings du contre-audit

État : corrections I3 implémentées et preuves bornées exécutées ; aucune clôture
canonique ni GO I4.
Référence auditée : `2b3567b1f77051c399772ee7ac787f7dd61ba407`.
HEAD de départ : publication documentaire `00cdc162` (rapport et preuves reçus,
rapport lu intégralement, empreintes de publication et archive vérifiées).
Worktree dédié existant : `/private/tmp/pocoma-v3-postaud`, branche
`feat/v3-architecture`. Checkout principal et autres worktrees préservés.

## Verification scope — déclaré avant modification

- Impact attendu : scripts de grants Identity, refus du préflight et politique
  PostgreSQL des diagnostics du seul banc Identity DEV ; tests et runbook associés.
- Slice primaire : COMMAND, ancre `runtime-command-consumption-worker`.
  Secondaires canoniques : NONE ; preuve directe `runtime-monolith` pour sa racine.
- Commande canonique composée : depuis `app/`,
  `./mvnw -pl runtime-command-consumption-worker,runtime-monolith -am test`.
- Architecture REQUIRED pour les règles d'isolation runtime ; commande canonique
  `./mvnw -pl architecture-tests -am test` (composable avec les ancres ci-dessus).
- Feedback ciblé sur les preuves Identity / deux racines / architecture ; il ne
  remplace pas une gate non filtrée et ne requalifie pas les FAIL antérieurs.
- Full reactor NOT REQUIRED : ni Wave majeure, ni main, ni restructuration Maven.
- DB CURRENT : WRITE V1–V6, READ technique V1 sur PostgreSQL 17.11 jetable ; aucune
  réécriture V6, baseline TRUSTED ou campagne historique séparée.
- Impact interdit : transactions/owner, contraintes V6, séquences, R1/R9, L2,
  budgets/oracles temporels, I4, HTTP, projections READ et autorité Pot.
- Escalade : toute modification productive hors de ces frontières impose une
  nouvelle déclaration avant son implémentation.
- Gates historiques I1/I2/I3 et réserves i5_r05/i5_r12 conservées sans réécriture.

## Frontière de confiance

Le propriétaire des objets, le provisionneur SQL, les administrateurs du serveur
et les extensions installées sont de confiance. Un superutilisateur PostgreSQL
peut changer les ACL, les fonctions, les contraintes ou les logs après inspection :
aucun préflight ne prétend l'empêcher. Les contrôles refusent une configuration
excessive observée avant démarrage ; ils ne retirent pas des grants et ne sont pas
une surveillance continue. Corriger les ACL/objets est une étape administrative
explicite, puis redémarrer les pools et refaire les contrôles.

Le banc DEV fermé n'autorise aucune vue/vue matérialisée lisible par READ sur
WRITE, ni fonction SECURITY DEFINER hors schémas système dans cette base.
Ce refus conservateur évite de prétendre analyser les dépendances du SQL dynamique
ou de fonctions opaques par un simple inventaire de privilèges directs.
Les scripts administratifs certifient les rôles auxiliaires nommés ; le Worker
ne dispose pas de leurs credentials et n'en invente pas les identités.

## Corrections et preuves avant/après

Code gelé : `c1172bf4a758a9077415d827ca88b359b6360ebb`.
Aucune modification productive après ce gel pendant la gate.

### CT-002 — MAJOR — objets intermédiaires

Avant : le banc original reçu a été recompilé et réexécuté contre les classes du
checkout audité `2b3567b1`, sur un nouveau serveur jetable PostgreSQL 17.11, port
55441. Les vues propriétaire sur `v3_eui_version.binding_token` et sur
`v3_identity_request(binding_token,admission_context)`, ainsi que la fonction SQL
SECURITY DEFINER externe `audit_aux.tokens()`, retournent des agrégats non nuls
sous login READ. Le préflight accepte ces trois profils ; le vrai Worker démarre
avec la vue exposée. Les ACL directes restent absentes : le privilège du propriétaire
de vue/fonction est le mécanisme d'accès, pas un SELECT direct du READ.

Après : la garde de démarrage et les scripts lifecycle/Discovery refusent le
profil intermédiaire sur toute la base WRITE. Le même banc indépendant, avec une
compilation externe de la garde du SHA gelé, refuse les trois témoins originaux.
Les nouveaux tests des deux racines couvrent aussi une vue externe, une vue
matérialisée accordée à PUBLIC, une vue `security_invoker=true` (déjà refusée par
PostgreSQL), et une fonction PL/pgSQL SECURITY DEFINER avec SQL dynamique et EXECUTE
PUBLIC. Le propriétaire et l'option invoker de chaque vue sont vérifiés.
Le SQL dynamique ne dépend pas d'une analyse statique de dépendances.

La correction est un **refus du profil**, pas une suppression magique d'ACL.
Les tests révoquent ensuite explicitement les grants, vérifient le refus PostgreSQL,
retirent l'objet non autorisé puis acceptent les scripts et un démarrage légitime.
Les fonctions invoker V6 et pgcrypto ne sont pas transformées en SECURITY DEFINER.
Les memberships du READ sont aussi inspectés dans le catalogue WRITE, y compris
ceux permettant SET ROLE sans héritage ; un rôle READ absent n'est pas accepté.

### CT-003 — MAJOR — mutation durable par rôles auxiliaires

Avant : nouvelle reproduction avec les scripts du SHA audité, acceptés malgré
`GRANT TRUNCATE ON v3_identity_event TO audit_a` puis
`GRANT TRUNCATE ON v3_identity_completion_event TO audit_i`. Connexions sous ces
logins, TRUNCATE et COMMIT réels : respectivement **2→0** et **1→0** lignes ; les
**3 positions** restent. Ces nouveaux volumes diffèrent du banc original de
l'audit (**4→0**, **2→0**, 6 positions), dont les preuves sont conservées inchangées.
Seul le jeu synthétique jetable avant correction a été détruit.

Après : contrôle des privilèges effectifs DELETE/TRUNCATE/TRIGGER sur les sept
tables Identity pour le réactivateur (lifecycle et Discovery) et l'injecteur
(worker-dev). L'injecteur a aussi une allowlist exhaustive SELECT/INSERT par
colonne ; INSERT d'événement et SELECT du token historique sont refusés.
`ON_ERROR_STOP` est explicite dans le script worker-dev.

Le test matriciel vérifie **2 rôles × 7 tables × 3 privilèges**, puis les grants
PUBLIC, un rôle intermédiaire avec INHERIT, et NOINHERIT suivi d'un vrai SET ROLE.
Les scripts refusent les excès ; le profil légitime est réappliqué. Sous chacun
des deux logins auxiliaires, TRUNCATE des deux tables d'événements retourne
**42501**, suivi d'un **COMMIT envoyé par JDBC** (PostgreSQL annule cette transaction
avortée, aucun effacement durable) ; une connexion administrative relit
l'empreinte durable inchangée. Le second banc indépendant conserve aussi ses
2 événements, sa complétion et ses 3 positions après refus.

Le Worker n'invente pas les identités des rôles auxiliaires : leur certification
est administrative et obligatoire via les scripts nommés. Son préflight ne
prétend pas inspecter des credentials auxiliaires inconnus. Un grant ajouté après
certification exige une nouvelle validation administrative ; il n'est pas rendu
inopérant par l'application déjà démarrée.

### CT-001 — MINOR — restriction de composition protégée

Le banc original reproduit encore le cas JDBC : holder identique entre T1/T2,
PID 209, XID 926 puis 928, `current` accepté sans verrou physique en T2.
Ce contre-exemple n'est ni nié ni « corrigé » par le GC.

Les deux racines livrées construisent WRITE Business avec JpaTransactionManager,
sans sélecteur de configuration permettant le manager JDBC de ce contre-exemple.
Les nouveaux tests extraient leur owner effectif, prennent les verrous et
constatent **55P03** par NOWAIT indépendant ; les holders ordinaires sont distincts.
Après commit/rollback, `current` et `apply` sans acquisition courante sont refusés.
Un holder JDBC prélié est refusé avant tout callback. Le participant conserve son
acquisition complète avant lecture ; son code et celui de l'owner L2 sont inchangés.

Décision bornée : pas de modification de l'owner ou des primitives d'autorité,
car aucun parcours de cette composition runtime autorisée ne reproduit le défaut.
La composition JDBC manuelle reste non certifiée. Toute future substitution de
manager doit résoudre ce témoin avant autorisation. Les variantes UNKNOWN/R9
existantes sont des preuves distinctes ; ce test nouveau ne prétend pas injecter
une panne UNKNOWN dans le holder prélié d'une composition déjà refusée.

### CT-004 — MINOR — DETAIL serveur sensible

Source exacte : PostgreSQL produit le token dans la DETAIL de la contrainte
UNIQUE `v3_identity_token_history`, avant que Java reçoive et expurge l'exception.
La nouvelle reproduction avant correction retrouve le marqueur synthétique une
fois dans le serveur ; aucun log brut n'a été exporté. La borne des paramètres
sur erreur est déjà zéro : elle ne supprime pas cette DETAIL générée par le serveur.

Correction : provisionnement explicite des defaults de la base WRITE neuve via
`configure-l3-identity-logging.sql`, raccordé à `worker-dev.py prepare` en mode
réel seulement. Verbosité terse, ERROR conservé (`log_min_messages=warning`), pas
de traces de statements/durée/échantillonnage, paramètres non journalisés ; préfixe
serveur SQLSTATE/PID/XID requis. Pas d'ALTER SYSTEM ni de changement V6/contrainte.
Les pools doivent être recréés ; la garde vérifie leurs paramètres effectifs et
refuse une surcharge de rôle rétablissant la DETAIL.

Après : collision UNIQUE par l'autorité Java, et CHECK impliquant token + contexte
sensible dans la ligne refusée. SQLSTATE **23505/23514**, ERROR et corrélation PID/XID
conservés, rollback confirmé, aucun token/contexte dans le graphe Java exporté ni
les logs PostgreSQL→Docker inspectés en mémoire. Les marqueurs ne sont jamais
écrits dans les reçus ; seules les valeurs booléennes sont conservées.
Un collecteur de production, agent de tracing, extension d'audit ou client debug
non éprouvé reste hors preuve ; aucune absence universelle de fuite n'est affirmée.

## Invariants et limites conservés

V6, JdbcIdentityAuthority, SpringAttemptTransactions, ExecuteIdentityRequest,
l'observation R9, les séquences et les oracles/budgets L2 sont inchangés par rapport
au SHA audité. Aucun changement d'arête Maven, de contrat métier, de contrainte,
de transaction, de verrouillage ou de publication. Les preuves avant ne sont pas
une fresh compilation complète de l'application : elles réemploient les classes
compilées du checkout audité, avec banc externe recompilé ; les limites et hashes
des entrées sont explicités dans les reçus.

Deux bases dans un cluster PostgreSQL 17.11 sont éprouvées. Des clusters séparés
avec catalogues divergents ne sont pas nouvellement certifiés ; le contrôle sur
WRITE est désormais explicite et fail closed. Aucun réglage de production,
consentement externe, projection READ, HTTP, qualification de charge ou I4.
Toutes les réserves I1/I2/I3, les FAIL historiques i5_r05/i5_r12 et l'état historique
Architecture SKIPPED restent consultables dans leurs rapports d'origine.


## Vérification exécutée et qualification des artefacts

Les [commandes exactes et résultats](../testing/evidence/L3-I3-resolution/Commands.json),
[entrées gelées](../testing/evidence/L3-I3-resolution/Proof-inputs.json),
[diff exact du correctif](../testing/evidence/L3-I3-resolution/Correction-code.diff.patch.gz)
et [dossier de preuves](../testing/evidence/L3-I3-resolution/README.md) sont livrés.
Les archives originales CT restent inchangées.

| Exécution | Résultat observé | Attribution et limite |
|---|---|---|
| Premier feedback ciblé | 73 tests, 0 failure/error/skip | Intermédiaire non gelé, hashes d'entrée non capturés au lancement ; **pas attribué au SHA final** |
| COMMAND + monolithe + Architecture, non filtré | **FAIL**, 1 867 tests, 1 failure (`i5_r12`), 0 error, 1 skip ; 49:04 | Sources au SHA gelé, sorties compilées préexistantes ; Architecture SKIPPED ; qualification bytecode ci-dessous |
| Architecture ciblée sur ces sorties | **FAIL**, 162 tests, 2 failures, 0 error/skip ; 43,265 s | Source/bytecode et format de lambda incohérents ; échecs conservés, aucun oracle modifié |
| Compilation neuve du même SHA + Identity/DEV et Architecture ciblés | **232 tests réussis**, 0 failure/error/skip ; 04:14 | **70 Identity/DEV + 162 Architecture**, checkout neuf, sans les tests longs L2 ; contribution bornée, pas remplacement de la gate non filtrée |

Commandes principales, depuis `app/` (filtres complets dans Commands.json) :

```sh
./mvnw -pl runtime-command-consumption-worker,runtime-monolith,architecture-tests -am test
./mvnw -o -pl architecture-tests -am '-Dtest=com.kartaguez.pocoma.architecture.*Test,com.kartaguez.pocoma.architecture.v3.*Test,com.kartaguez.pocoma.architecture.ccr.*Test,com.kartaguez.pocoma.architecture.ept.*Test' -Dsurefire.failIfNoSpecifiedTests=false test
```

Le seul échec de la commande non filtrée est
`V3WorkerResourcesPostgresTest.i5_r12:896`, dans `Runtime.close` via
`I5TemporalV2Postgres.campaign` : `runtime condition deadline`. Même assertion que
la réserve historique ; aucune attribution nouvelle de cause racine n'est faite.
Le skip natif reste `TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`, propriété
`step3b.measure` absente ; aucun test Identity n'est SKIPPED. Les 13 tests des deux
racines réussissent déjà dans cette exécution, avant leur confirmation en build neuf.

### Écart source/bytecode découvert pendant la vérification

Les deux FAIL Architecture sont conservés dans `architecture-stale-output.tar.gz`.
`BindingLockOwnershipTest.everyProductionSourceHasImportedBytecode` trouve une
source déclarant `engine.write.pot.exception.MissingTerminalConsumptionFailureException`
alors que le `.class` préexistant déclare encore `engine.exception`.
`EventPublicationContractArchitectureTest.currentParticipantAnnotationsAndBodyGuardsCannotDisappear`
ne reconnaît pas le garde dans le lambda précompilé `lambda$0` de
`ExternalIdentityBindingFactJdbcRepository` ; l'oracle attend `append` ou
`lambda$append$...`. Ces sources et oracles ne sont pas modifiés par le correctif.
Les sorties `javap`, empreintes et limites sont dans
[Bytecode-qualification.json](../testing/evidence/L3-I3-resolution/Bytecode-qualification.json).

Une copie Git neuve, HEAD détachée exactement à `c1172bf4`, a été créée dans
`/private/tmp/pocoma-i3-architecture-fresh`. Elle n'a reçu aucun changement de
source/POM/oracle ; ses sorties compilées sont neuves. La classe est alors importée
dans son package déclaré et le lambda compilé porte `lambda$append$0` ; les deux
oracles passent parmi les 162 contrôles. Le build frais confirme aussi tous les
70 tests Identity/DEV. Le checkout initial et ses sorties préexistantes n'ont pas
été nettoyés pour masquer cet écart.

**Qualification :** la commande non filtrée n'est pas une reconstruction intégrale
source/bytecode cohérente à partir de zéro. Son FAIL est conservé ; le feedback
propre démontre les propriétés bornées sur les bonnes classes. Aucun résultat de
ce dossier ne certifie une gate canonique réussie. Les tests longs L2 n'ont pas été
rejoués après cette découverte ; leur réserve n'est pas levée par supposition.

Les vérifications DB indépendantes avant/après et leurs scripts sont archivés
séparément ; les marqueurs serveur sont inspectés en mémoire, jamais exportés en
clair. Le collecteur de preuves refuse explicitement des XML plus anciens que la
borne de la contribution ; son témoin négatif est conservé. Les commandes Git de
lecture, vérification d'empreintes et `git diff --check` complètent ces preuves.
Le wrapper Python est vérifié syntaxiquement et ses stages SQL sont éprouvés par
les fixtures/serveurs ; aucun nouveau packaging exec-jar ni prepare CLI intégral
n'est revendiqué dans ce mandat.

## Rapport d'implémentation et arrêt de périmètre

- Start HEAD : `00cdc16253ef9900224c7c6be90a0a814d2baf73` ; référence applicative
  auditée `2b3567b1f77051c399772ee7ac787f7dd61ba407`.
- Commit productif final : `c1172bf4a758a9077415d827ca88b359b6360ebb`. La publication
  de ce rapport/preuves constitue un commit documentaire séparé, communiqué à
  la livraison ; aucun input applicatif ne change après le gel.
- Impact attendu/observé : garde Identity de démarrage, scripts de grants et
  politique de logs DEV ; tests, outillage de collecte et runbook. **Une seule
  classe Java productive modifiée**, aucune dépendance/POM/migration/transaction.
- Slices déclarées : **COMMAND** ; preuve directe **runtime-monolith** ; secondaires
  canoniques NONE. Franchissement productif inattendu : **NONE**. L'écart d'artefacts
  compilés est un finding de vérification distinct, explicité ci-dessus.
- Architecture globale : **REQUIRED**, tentative composée SKIPPED après le FAIL
  amont ; contrôles ciblés complets du module exécutés et confirmés en build neuf.
  **Gate globale non acquise**, aucune dispense implicite.
- Full reactor : **NOT RUN / NOT REQUIRED** pour cette correction bornée ; aucune
  Wave majeure, intégration main, restructuration ou certification globale.
- DB : **CURRENT V3 WRITE V1–V6 / READ technique V1**, plus migrations historiques
  effectivement exécutées transitivement par les tests existants de la commande
  composée/Architecture. Aucune campagne historique séparée ni baseline TRUSTED.
- Push/divergence finale : confirmés dans le reçu de livraison Git ; aucune mise
  à jour forcée ni modification des branches/worktrees préexistants.
- Working tree principal : HEAD `90a3e9bc`, branche `feat/runtime-monolith` ; état
  sale préservé, relevé en lecture seule dans les preuves. Une modification externe
  de `docs/operations/cmd-start-runtimes.md` a été observée pendant le mandat et
  laissée intacte ; aucune identité parfaite des contenus concurrents n'est inventée.
- Arrêt après ces corrections I3. Contre-vérification/arbitrage indépendants restent
  distincts. **Aucun PASS canonique, aucune levée de réserve I1/I2/I3, aucun GO I4.**
