# I5 — Correctif borné, contre-exemple temporel et arrêt de qualification

**FAIL — TEMPORAL QUALIFICATION STOPPED — DECISION REQUIRED.** Aucun certificat90s n'est acquis. AUD-I5-01 reste OPEN. AUD-I5-02 est implémenté et ses preuves locales passent ; la gate composée ne passe pas, donc sa clôture reste non acquise. I5, R-AUD-I3-03 et L2 ne sont pas déclarés CLOSED/PASS. Audit indépendant toujours attendu.

## Reconnaissance et provenance

SHA initial constaté sur `feat/v3-architecture` : `1281d468e399aa2a34a0a0a84c74b04b0da69f78`, identique à la tête distante vérifiée par `git ls-remote`. Aucun écart depuis le plan audité. Worktree propre réutilisé : `/private/tmp/pocoma-v3-postaud`. Le répertoire de départ était sur `feat/runtime-monolith`, HEAD `90a3e9bc1a1fdd325d0c36e543824239cd85a431`, avec travaux préexistants. Tous les worktrees ont été inventoriés ; les travaux étrangers et les anciens worktrees prunables sont préservés. Aucun reset, clean, stash, force push ou écrasement.

Le GO humain autorise les seuls AUD-I5-01/02 et amende explicitement les gates initiales I5. Politique Reactor, plan audité, plans I4/I5 et contrats Discovery/observabilité/certification examinés ; les signatures et comportements réels Engine/RequestProgress, worker.event/safely, renouvellements, settings et adaptateur Micrometer ont été inspectés.

Gels testés :

- `05b74814abe810a22f1f6a4c5125d4374325f6fa` : gate composée complète. Le commit précédent `b477175c909ee3848d4b6519e5edad658779b21e` porte le correctif, les oracles et le contrat ; le gel05b74814 donne des noms de preuves distincts aux variantes retenues WRITE/READ.
- `8990737ce0b6f7df155d7e80aa698657815dc5ef` : deux corrections de fixtures, puis ciblage r12/m03. C'est le SHA applicatif final testé. Le code productif est identique à celui testé dans la première gate ; seules deux lignes de tests supplémentaires changent.
- Le SHA final publié est celui du commit documentaire contenant ce rapport, communiqué dans la livraison finale ; il ne réattribue aucun PASS au code non exécuté.

Les1231 empreintes d'entrées du premier reçu correspondent au commit de gel. Les cinq sources Java modifiées ont des empreintes de classes compilées. Le contrat conserve son empreinte pré-campagne ; aucun fichier applicatif ou script n'a été modifié pendant une exécution.

## Périmètre déclaré et implémentation

COMMAND primaire (`runtime-command-consumption-worker`), MONOLITH complément (`runtime-monolith`), aucune slice canonique secondaire. Aucun franchissement inattendu. Gate Architecture globale et Full Reactor **NOT REQUIRED / NOT RUN** : aucun POM, module, dépendance, règle d'architecture, frontière, port, migration ou défaut productif modifié. Base : CURRENT V3, PostgreSQL17.11 épinglé au digest historique ; aucune nouvelle certification HISTORICAL/BASELINE.

Le seul delta productif est de trois lignes dans `V3DiscoveryLoop` : après retour normal de `discoverRequests` et conservation du `result.next()`, `worker.event(DISCOVERY_TOUR, CONFIRMED, 1, Duration.ZERO)` est émis si le résultat réel `tourFinished()` est vrai. Aucun nouvel état ou suivi de tours ; aucun événement inventé pour les sources non burnables. Les owners, frontières physiques, claims/leases/fencing/recovery I3, protocole I4, ConsumptionEngine/WorkerCapacity, quatre pools, renouvellements, drainage, maintenance, SQL et profils restent inchangés.

AUD-I5-01 : les seuils circulaires de r05/r12 sont remplacés par le contrat immuable et `I5TemporalBudgetTest`. Les observations de validation ne modifient aucun seuil. Les journaux locaux aux tests séparent Worker/autorité/voie, tous les candidats et omissions ; ils observent le service depuis due initiale, l'opération complète et le nettoyage. Les vecteurs SQL extérieurs contrôlent tokens, extension durable et ancienne échéance avec marge. Le nouvel oracle a effectivement refusé le contre-exemple R2.

## Contrat temporel figé, jamais certificat implicite

Version [I5-C-90-v1](../testing/evidence/L2-I5-audit-remediation/temporal-contract-v1.properties), commitée avant campagne. SHA-256 : `4e07d322074d020f0734d29f05a95b24a073e8caf94d60ba2dae853cda3dc806`.

Override existant : `pocoma.v3.worker.lifecycle.lease-duration-ms=90000`. Le défaut30s reste inchangé et **NON CERTIFIÉ**. Le contrat n'a pas été retouché après le FAIL.

| Paramètre | Valeur préalable |
|---|---|
| Leases / claims / cadence I |90s /60s /10s|
| T, opération complète |5s, emprunt + unité physique + retour/nettoyage|
| δ, vieillissement |5s|
| J, retard cumulé pertinent |1s|
| P, attente inactive / q, polling actif |1s /10ms|
| S, sécurité |2s|
| R, appels supplémentaires leases |1 maximum par Worker/autorité/cycle, limite du scénario uniquement|
| Cycles et watchdog d'observation |2 ;80s/cycle, distinct des seuils d'acceptation|
| W / M_W / B |1 ou2 /1 ou4 /1 ou32|
| M_C |Command1, I5ReadPotProbe2, I5ReadCompletionProbe3|
| Partitions |4/C en W1 ;0\|1 et2\|3 par Worker en W2|
| Pools/autorité/Worker |Business=M_W+2, Control=M_W+6, minIdle=max|
| Connexions de rôles W1 / W2 |20 ou32 /40 ou64 ; serveur100, réserve16|
| Charge / fautes |préparations RO réelles retenues, boucles/maintenance normales, un GC explicite par cycle ; omission transitoire injectée WRITE et READ au cycle2|

`C(N,R)=(N+R)×T+P+(N+R)×q+J` et `δ+I+C+S < TTL`, strictement. Aucun lot plein présumé ; aucune durée calculée d'après le maximum mesuré.

| Voie par Worker | N/H | C nominal | C avec R1 | δ+I+C(R1)+S | TTL |
|---|---:|---:|---:|---:|---:|
| Leases WRITE W1 |4|22,04s|27,05s|44,05s|90s|
| Leases READ W1 |8|42,08s|47,09s|64,09s|90s|
| Leases WRITE W2 |2|12,02s|17,03s|34,03s|90s|
| Leases READ W2 |4|22,04s|27,05s|44,05s|90s|
| Claims M_W1, singleton, R0 |1|7,01s|sans objet|24,01s avec R0|60s|
| Claims M_W4, singleton, R0 |4|22,04s|sans objet|39,04s avec R0|60s|

C-PRE rejette30s/N8/T5 et l'égalité au TTL ; C-NEG rejette opération hors borne, service hors borne, excès d'appels, ancienne échéance dépassée et incarnation remplacée, sans utiliser une calibration pour relever le seuil. C-OP inclut attente de connexion/retour/nettoyage ; δ est borné par l'âge SQL de l'échéance et l'âge monotone de sa référence locale, sans soustraction d'horloges murales JVM/PG. C-SERVICE conserve la première due après omission. C-JITTER cumule les intervalles pertinents, charge les pauses actives à T et ne remplace pas J par un maxGap de heartbeat. C-PG lit tokens/échéances et clock_timestamp par autorité depuis une connexion extérieure, après visibilité du commit. C-LOAD contrôle les préparations RO, PID/XID, capacités et connexions réelles.

La compatibilité arithmétique à90s est conditionnelle à ces hypothèses ; elle ne démontre pas R1. Aucun résultat ne vaut certificat de production, de toute charge/concentration de Workers, de panne durable PostgreSQL/réseau ou de pause JVM non bornée. Les délais de reprise/takeover des essais30s ne sont pas transférés à90s. Si30s devient une condition de clôture, une décision explicite distincte reste indispensable.

## Huit variantes C et contre-exemple conservé

PASS local d'une ligne W1 signifie uniquement ses nouveaux oracles exécutés au gel05b74814. La gate de cette tentative a échoué ; aucun certificat de l'ensemble ou du gel8990737c n'est transféré.

| Variante | Cycles | Appels leases WRITE/READ, nominal → omission | Résultat | Gel |
|---|---:|---|---|---|
|W1-B32-MW1-held0|2|1/2 → 2/3|R0 puis R1 sur WRITE/READ ; oracles locaux PASS ; max service descriptif 0.129065s|05b74814|
|W1-B32-MW1-held1|2|3/4 → 3/5|R0 puis R1 sur WRITE/READ ; oracles locaux PASS ; max service descriptif 0.994666s|05b74814|
|W1-B32-MW4-held-1|2|1/2 → 2/3|R0 puis R1 sur WRITE/READ ; oracles locaux PASS ; max service descriptif 0.190411s|05b74814|
|W1-B1-MW4-held-1|2|4/8 → 5/9|R0 puis R1 sur WRITE/READ ; oracles locaux PASS ; max service descriptif 0.307682s|05b74814|
|W2-B32-MW1-mixed|2 observés|Worker1 READ :4 → 6, plafond second cycle5|FAIL enveloppe R1 ; seconde omission non injectée ; aucun certificat|8990737c|
|W2-B32-MW4-mixed|0|sans objet|NOT RUN ; contribution INCONCLUSIVE après arrêt|sans objet|
|W2-B1-MW1-mixed|0|sans objet|NOT RUN ; contribution INCONCLUSIVE après arrêt|sans objet|
|W2-B1-MW4-mixed|0|sans objet|NOT RUN ; contribution INCONCLUSIVE après arrêt|sans objet|

Les variantes W1 contrôlent les12 leases et tous les claims des préparations retenues, sans choisir les seuls candidats renouvelés. Les anciens claims abandonnés sont distingués avant validation et vérifiés non renouvelés. Les journaux montrent aussi des lots B32 partiels. Les maxima ci-dessus sont descriptifs, jamais des paramètres d'acceptation.

Le premier W2, B32/M_W1, a terminé son cycle nominal. Au cycle2, READ du Worker1 exécute six appels pour quatre candidats, alors que N+R autorise cinq appels :

| Ordre READ | Candidat | Retour |
|---:|---|---|
|1|I5ReadPotProbe/2 (`...0001/2`)|omis, faute injectée|
|2|I5ReadCompletionProbe/2 (`...0002/2`)|omis, non injecté|
|3|I5ReadPotProbe/2|renouvelé|
|4|I5ReadCompletionProbe/2|renouvelé|
|5|I5ReadPotProbe/3|renouvelé|
|6|I5ReadCompletionProbe/3|renouvelé|

Deux appels de revalidation supplémentaires sont donc observés, pas seulement deux lignes omises dans un même lot. Le checker Java refuse `expected1 / observed2` ; le contrôle séparé des appels confirme6>5. Le détenteur du verrou de la seconde omission n'a pas été observé au moment précis : aucune attribution certaine à Discovery, au réseau ou à PostgreSQL n'est inventée.

Les assertions SQL d'extension durable, tokens conservés et ancienne échéance encore future avec marge2s ont été exécutées avant le rejet R ; ce FAIL ne démontre pas une violation du fencing ou une expiration. Il démontre que l'enveloppe R1 revendiquée n'est pas satisfaite par ce scénario. Les mesures temporelles rapides n'autorisent pas à changer cette condition préalable.

Le contre-exemple est dans [R1-counterexample.json](../testing/evidence/L2-I5-audit-remediation/R1-counterexample.json), avec appels complets, due originale et empreintes des vecteurs/journaux archivés. **Qualification arrêtée** : aucune exclusion rétroactive, aucune répétition jusqu'au vert, aucun relèvement silencieux de R. Les trois autres W2 restent NOT RUN. Toute reprise demande une décision explicite, des hypothèses/majorants défendables avant validation et un nouveau contrat immuable ; aucune architecture supplémentaire n'est implémentée.

## Métrique Discovery et négatifs

Les12 tests Worker et les2 tests de budget passent sans skip. u11/u12 utilisent le vrai DiscoveryEngine, des clés préparées et un journal séparé du retour owner : COMMAND et IDENTITY_REQUEST générique, plusieurs pages/rotations C/p, tours sans nouvelle admission, source vide/disparue, capacité initiale/après préfixe/reprise, absence de lease/protection, source/admission/fence, ROLLED_BACK/COMMITTED sans résultat/UNKNOWN, Event non burnable et observer fautif. Ces tests ne prétendent pas activer Identity métier ni prouver SQL avec des doubles de ports.

m03 passe avec la vraie boucle, le vrai Engine et les owners/adaptateurs JDBC. Le registre réel présente `pocoma.v3.rotation.tours{consumption="Command",loop="discovery"}` :0 sur pages intermédiaires,1 à la première fin,2 après un second tour sans nouvelle admission ; les trois slots SQL restent les mêmes. Source vide confirmée :1. CAPACITY, absence d'ownership et rollback réel après admission :0, SQL sans admission indue. L'observer défaillant conserve le même progrès. L'omission volontaire est rejetée à la fin ; l'émission prématurée est rejetée dès la page intermédiaire malgré une série présente. L'injection directe n'est que la faute négative, pas la preuve positive.

La première exécution m03 prenait264,204s : le catalogue temporairement réduit au stepping laissait attendre huit fois30s les ACK du catalogue mixte lors du nettoyage. La restitution du catalogue initial avant nettoyage corrige cette fixture, sans changer ses oracles. Au gel8990737c, m03 passe en27,206s. Les métriques restent locales/volatiles et une panne d'observer peut perdre une observation ; aucun timestamp ou port productif ajouté.

## Gate, diagnostics et coût réel

Commandes depuis `app/` :

```sh
./mvnw -pl runtime-command-consumption-worker -am -Dtest=I5TemporalBudgetTest,V3ConsumptionWorkerTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl runtime-command-consumption-worker,runtime-monolith -am test
./mvnw -pl runtime-command-consumption-worker -am '-Dtest=V3WorkerResourcesPostgresTest#i5_r12,V3WorkerMetricsTest#i5_m03' -Dsurefire.failIfNoSpecifiedTests=false test
```

Le ciblage initial a été exécuté six fois : trois diagnostics de compilation conservés (accès de fixture à la boucle puis registre non AutoCloseable), trois passages réussis ;14 tests requis, zéro skip. Coût Maven cumulé37,448s.

La gate composée sans filtre au gel05b74814 : **FAIL**, exit1,1649 tests/251 XML, un `i5_r12` en erreur avant campagne et un skip exploratoire historique `TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`. Les405 méthodes requises sont contrôlées : seule r12 ne passe pas. Les67 exigences physiques I5 inchangées et les exigences I2/I3 corrigé/I4 sont vérifiées fraîches, sans problème. MONOLITH passe. Temps mural du reçu2857,465s (47min37,465s) ;956 fichiers physiques frais archivés une fois avec les XML et le log.

Cause de cette première erreur : mon nom de fixture W2 contenait une majuscule, refusée par la règle existante `[a-z][a-z0-9-]{1,31}`. Le gel8990737c corrige ce nom et le nettoyage métrique. Le ciblage supplémentaire était justifié par ces deux défauts avant de repayer la gate : **FAIL**, exit1,65,775s ; m03 PASS et r12 FAIL sur R2. Ce ciblage est classé DIAGNOSTIC pour la livraison, pas calibration ni certificat.

La gate composée au gel8990737c est **NOT RUN**, conformément à l'arrêt de qualification après le contre-exemple. Aucun PASS de gate n'est revendiqué. Le diagnostic Surefire de JVM amont arrêtée tardivement après556 tests réussis est conservé ; il existe aussi dans F historique et n'est pas la cause du FAIL.

**Coût cumulé mesuré des commandes :2960,688s, soit49min20,688s.** Les coûts C et métriques sont inclus, pas ajoutés une seconde fois. Lecture, implémentation, rédaction et contrôles documentaires ne sont pas chronométrés individuellement. Aucun second passage complet inutile après R2.

NOT RUN : gate READ JDBC séparée, Architecture globale, Full Reactor, campagnes A/B autonomes, reproductions autonomes F01–F05, campagne historique de migrations, trois W2 restantes. Les migrations exécutées naturellement par la fermeture Maven restent un coût de cette gate, sans nouvelle certification HISTORICAL ou BASELINE.

## Preuves, intégrité et réutilisation

[Manifeste additionnel](L2_I5_Audit_Remediation_Proof_Manifest.json) : allocations exactes, huit variantes, états manquants/FAIL, hash du contrat, méthodes, coûts, empreintes compilées et fichiers physiques attendus. Les sept exigences physiques nouvelles absentes/incomplètes sont conservées comme problèmes, pas ignorées pour produire PASS.

[historical-integrity.json](../testing/evidence/L2-I5-audit-remediation/historical-integrity.json) vérifie115 fichiers historiques I5 byte-identiques et les hashes des cinq archives C/R/M/A/F. Les ressources A/B WRITE/READ et leurs membres physiques sont REUSED pour le périmètre inchangé au gel9a315e16. Les anciens C restent DIAGNOSTIC/insuffisants pour AUD-I5-01. I2/I3/I4, r10/r11 et w02 restent inchangés ; leurs régressions de fermeture sont exécutées naturellement et archivées fraîches, sans campagne autonome ajoutée.

Archives additionnelles : `deterministic.tar.gz`, `gate-1-failed-05b74814.tar.gz`, `targeted-R2-counterexample-8990737c.tar.gz`, reçus exacts, contre-exemple structuré et contrat. Aucune copie des anciennes grandes archives. Les fonctions réutilisables des vérificateurs existants contrôlent méthodes, sources, classes et fraîcheur ; leurs témoins missing/skip/duplicate/stale/mutation sont rejetés. Aucun nouveau framework de collecte, aucune invocation du collecteur historique C/R/M/A/F suivie d'erreurs ignorées.

Autres commandes : reconnaissance Git status/branch/rev-parse/log/worktree/diff/ls-remote ; cat/sed/rg ; Python standard JSON/XML/tar/SHA-256 ; formatage AOSP des seuls Java modifiés ; `git diff --check`. GitHub/Docker ont utilisé l'autorisation d'environnement nécessaire ; aucun rejet d'auto-review. Les empreintes et le diff sont revérifiés avant commit/push normal sur la branche autorisée.

## Fichiers modifiés et décision restante

Liste exacte, relativement à la racine ; seuls le premier fichier Java et ses trois lignes sont productifs :

- `app/orchestrator-poll-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/poll/consumption/v3/V3DiscoveryLoop.java`
- `app/orchestrator-poll-consumption/src/test/java/com/kartaguez/pocoma/orchestrator/poll/consumption/v3/V3ConsumptionWorkerTest.java`
- `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/I5TemporalBudgetTest.java`
- `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3WorkerMetricsTest.java`
- `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3WorkerResourcesPostgresTest.java`
- `docs/v3/L2_I5_Worker_Development_Runbook.md`
- `docs/v3/L2_I5_Audit_Remediation_Proof_Manifest.json`
- `docs/v3/L2_I5_Audit_Remediation_Report.md`
- `docs/testing/evidence/L2-I5-audit-remediation/temporal-contract-v1.properties`
- `docs/testing/evidence/L2-I5-audit-remediation/deterministic.tar.gz`
- `docs/testing/evidence/L2-I5-audit-remediation/gate-1-failed-05b74814.tar.gz`
- `docs/testing/evidence/L2-I5-audit-remediation/gate-1-failed-receipt.json`
- `docs/testing/evidence/L2-I5-audit-remediation/targeted-R2-counterexample-8990737c.tar.gz`
- `docs/testing/evidence/L2-I5-audit-remediation/fixture-targeted-receipt.json`
- `docs/testing/evidence/L2-I5-audit-remediation/R1-counterexample.json`
- `docs/testing/evidence/L2-I5-audit-remediation/historical-integrity.json`

Écarts au résultat attendu : deux défauts de fixtures corrigés ; un ciblage supplémentaire justifié ; contre-exemple R2 sur le premier W2, trois autres W2 non exécutés et aucune nouvelle gate complète après arrêt. Aucun élargissement productif ou changement du contrat accepté. R1 n'est pas devenu une garantie générale du runtime ; le fait qu'un run soit rapide ou conserve ses échéances ne permet pas d'en ignorer le dépassement.

La livraison est un correctif métrique testé et un dossier temporel contradictoire en FAIL, **pas** `I5 CORRECTIVE IMPLEMENTED — LOCAL BOUNDED PASS`. La prochaine décision porte sur une enveloppe préalable justifiable et son protocole de validation, avec audit indépendant. Aucune certification90s/30s/production, aucune clôture I5 ou R-AUD-I3-03, aucun L2 PASS.
