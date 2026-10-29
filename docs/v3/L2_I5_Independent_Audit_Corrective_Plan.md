# L2 / I5 — Plan correctif des findings indépendants AUD-I5-01 et AUD-I5-02

Statut : **PROPOSITION À VALIDER — AUCUNE AUTORISATION D’IMPLÉMENTATION DÉDUITE**.

Référence examinée : `feat/v3-architecture`, livraison `c6f3de1a482a7e67cf91e2fa9554d49e2b23270d`. Les campagnes historiques portent sur le gel applicatif `9a315e16567042d0b9bd017486d9043216b3ecd7`, distinct du commit documentaire de livraison. L’audit indépendant communiqué conclut FAIL I5 pour les deux findings ci-dessous, sans nouvelle violation démontrée de l’atomicité métier ou du fencing.

Ce document propose un correctif borné, pas une nouvelle architecture ni une nouvelle certification complète I5. Sa validation devra aussi approuver explicitement l’enveloppe temporelle et les gates de ce correctif. Aucun code, test, script, profil ou preuve historique n’est modifié dans cette étape ; aucun commit n’est créé.

## 1. Références et constat

- [Politique normative de vérification](../testing/Reactor_Verification_Policy.md), notamment §§3, 11, 14, 17–21 et 29–30.
- [Plan I5](L2_I5_Implementation_Plan.md), §§14–15, 22–24, 30 et compléments F02/F05/T04.
- [Contrat I4](L2_I4_Implementation_Plan.md), §§8–10 : `RequestProgress`, progrès certain et tour volatil.
- [Rapport de livraison](L2_I5_Execution_Report.md), [reçu des gates](../testing/evidence/L2-I5/Gate-index.json), dossiers [WRITE-C](../testing/evidence/L2-I5/resources/WRITE-C.json) et [READ-C](../testing/evidence/L2-I5/resources/READ-C.json).

L’examen porte sur les sources réellement présentes au SHA demandé et sur les XML/fichiers physiques de l’archive F. Aucun Maven, PostgreSQL, Docker ou reproducer n’a été exécuté pour rédiger ce plan.

## 2. AUD-I5-01 — cause racine précise

Dans `V3WorkerResourcesPostgresTest.i5_r05`, lignes 1920–1952 :

```text
N = max(nombre de leases WRITE, nombre de leases READ) = 8
m = max(operation observée pendant cette même exécution)
s = max(service observé depuis due pendant cette même exécution)
leaseBudget = max(N*m + 2s + N*10ms, s + 1s)
serviceBudget = max(leaseBudget, M_W*5s + 1s)
assert chaque service observé < serviceBudget
assert 12s + leaseBudget < leaseDuration
```

`leaseBudget >= s+1s` rend l’assertion portant sur les services de l’échantillon déjà capturé automatiquement vraie. L’observer continuant à alimenter la liste, un échantillon ultérieur peut encore faire échouer le test ; cela ne rend pas indépendante la validation des échantillons ayant construit le seuil. Le maximum des opérations, lui aussi issu du même essai, remplace la borne préalable de 5s dans l’inégalité de faisabilité du lease.

Dans `i5_r12`, lignes 556–582 :

```text
m = max(operation des deux Workers, même exécution)
s = max(service des deux Workers, même exécution)
candidateBudget = 8*m + 2s + 80ms
serviceBudget = s + 1s
budget = max(candidateBudget, serviceBudget)
assert 12s + budget < leaseDuration
```

Ici le budget du cycle dépend également des observations qu’il doit qualifier. Il n’existe pas de comparaison indépendante de chaque service à un majorant préfigé. L’agrégation mélange Worker, autorité et voies claims/leases ; le coefficient 8 reste conservateur pour les partitions réparties de r12, mais ne constitue pas une analyse par voie.

Les contrôles `operation < 5s`, `maxGap < 1s`, les durées de leases/claims fixées avant construction et les comparaisons aux anciennes échéances PostgreSQL restent des contrôles distincts et utiles. Ils ne suffisent pas à transformer ces budgets calculés après mesure en preuve temporelle préalable. Le maximum d’un intervalle de heartbeat ne borne pas non plus le retard cumulé d’un cycle.

## 3. Contrat temporel proposé, figé avant campagne

### 3.1 Séparer quatre activités

1. **Hypothèses et majorants préalables.** Figer un contrat versionné, les propriétés réellement injectées et leur empreinte avant démarrage des Workers : W, M_W/M_C, partitions candidates, B, voies, durées, charge, fautes admises, nombre de cycles, oracles et règles d’échec. Les seuils dépendent de ces seuls paramètres. Vérifier leur faisabilité avant tout SQL de campagne.
2. **Calibration facultative.** Les observations historiques r05/r12 restent disponibles comme calibration et diagnostic. Une calibration supplémentaire n’est pas nécessaire par défaut. Si elle devient nécessaire, ses sorties ne portent aucun verdict C et ne sont jamais réutilisées comme échantillons de validation. Toute modification du contrat crée une version et impose une nouvelle validation, avec conservation des échecs.
3. **Validation indépendante.** Utiliser de nouvelles exécutions, nouveaux schémas et nouveaux échantillons sur le correctif gelé. Comparer chaque opération et chaque service au contrat immuable. Aucune branche `budget=max(budget,observed+margin)` ni seuil dérivé d’un maximum de cette campagne. Les maxima restent des résultats descriptifs.
4. **Échéances PostgreSQL effectives.** Valider séparément, par observateur extérieur, l’extension durable de chaque incarnation avant son ancienne échéance avec marge. Le budget JVM, un callback, une gauge Hikari ou DRAINING ne remplacent jamais cet oracle.

### 3.2 Modèle conservateur et incompatibilité avec 30s

Reprendre les valeurs déjà préalablement proposées dans I5 : cadence I=10s ; objectif d’opération complète T=5s ; retard JVM/ordonnancement cumulé J=1s ; attente inactive P=1s ; minPoll q=10ms ; sécurité S=2s. T couvre emprunt, unité physique, retour et nettoyage local ; le découpage 1s emprunt + 3s unité + 1s retour/nettoyage est une hypothèse à qualifier, pas une conséquence automatique des timeouts. Le socketTimeout5s ne prouve pas à lui seul T=5s.

Pour une voie leases indépendante, avec N candidats pouvant chacun nécessiter un lot partiel :

```text
service normal maximal depuis due : C(N,0) = N*T + P + N*q + J
condition nominale : I + C(N,0) + S < leaseDuration
```

Pour N=8 côté READ d’un Worker complet : `10 + 8*5 + 1 + 0,08 + 1 + 2 = 54,08s`. Côté WRITE, N=4 donne 34,04s. **Le lease30s n’est pas certifiable par ce modèle**, y compris avec B32 : des lots partiels interdisent de présumer trois lots toujours pleins. Le test doit rejeter cette combinaison avant campagne, même si toutes les anciennes mesures sont rapides.

Pour r12 réparti 0|1 / 2|3, chaque Worker a N_WRITE=2 et N_READ=4 : les bornes nominales sont 24,02s et 34,04s par Worker. La concurrence de deux Workers charge le même serveur ; elle ne fusionne pas leurs voies. Le gain par répartition ne suffit donc pas davantage à justifier 30s pour READ.

Deux précisions doivent être intégrées plutôt que cachées dans un maximum observé :

- **Vieillissement de l’échéance.** Une durée SQL démarre avant son retour Java. Budgéter séparément δ, intervalle maximal entre le timestamp SQL fondateur de l’échéance et le point de référence local utilisé pour calculer la prochaine due. Proposition conservatrice δ<=5s, à vérifier avec le bornage de l’appel et du transfert local. Exiger `δ + I + C + S < durée`. Une borne plus serrée éventuelle doit être justifiée avant campagne, pas soustraite après mesure.
- **Revalidations F05.** N ne borne pas les appels supplémentaires après omission qualifiée. Déclarer R, nombre maximal d’appels supplémentaires par voie et cycle, et mesurer depuis la due initiale conservée. Utiliser `C(N,R)=(N+R)*T + P + (N+R)*q + J`. Une expiration de fenêtre de revalidation ne démontre ni renewal réussi ni fin physique. Ne jamais extrapoler ce modèle à des omissions répétées sans borne ou à UNKNOWN.

### 3.3 Choix concret soumis à validation

**Proposition : qualifier les huit variantes existantes de r05/r12 avec le lease expérimental90s déjà utilisé pour B1, y compris B32 ; conserver claims60s et cadence10s.** Aucun changement de `LifecycleSettings.pilot()`, du protocole renewal, des quatre pools ou de leur dimensionnement. Lancer les fixtures et documenter l’usage qualifié avec l’override existant `pocoma.v3.worker.lifecycle.lease-duration-ms=90000`.

L’enveloppe proposée comprend deux cycles par variante : un cycle nominal et un cycle avec une omission qualifiée transitoire contrôlée par autorité, libérée pour permettre la revalidation normale. R=1 maximum par voie leases et cycle est ici **une limite explicite du scénario et du certificat**, pas une propriété générale présumée du Worker. Le test compte tous les appels/omissions, y compris les non injectés ; il ne masque pas une seconde omission. Les expirations/retraits/réacquisitions ne valent pas succès du même lease.

Avec le cas maximal N=8, R=1 et δ=5s : `5 + 10 + 9*5 + 1 + 0,09 + 1 + 2 = 64,09s < 90s`. Le choix90s réutilise une durée expérimentale existante et découle des majorants préalables, sans consulter les maxima historiques pour choisir T ou le TTL. Il n’est pas présenté comme la durée mathématique minimale. Sans reprise ni vieillissement, les 54,08s suffisent déjà à exclure30s.

Pour les claims singleton, raisonner par Worker/autorité, H<=M_W, avec **une seule voie disponible pour les autres handles non en vol** : `δ + I + H*T + P + H*q + J + S <=39,04s` pour M_W4, donc inférieur à60s. Aucun budget fini n’est attribué au handle d’un appel UNKNOWN ou physiquement bloqué sans borne. Les deux voies claims existantes restent inchangées.

**Portée de l’acceptation :** les valeurs par défaut à30s restent un pilote non certifié par C. Si la clôture exige explicitement la certification de30s, cette proposition ne suffit pas : il faut établir au préalable un autre majorant indépendant compatible avec30s, ou approuver une modification de configuration fondée sur la contradiction. Diminuer T pour coller aux anciens maxima, supposer B32 toujours plein ou supprimer la marge est interdit. Un certificat90s ne doit jamais être présenté comme un certificat30s.

### 3.4 Oracles temporels nouveaux

| Oracle | Attendu et témoin |
|---|---|
| C-PRE | Contrat figé avant campagne ; rejet déterministe de30s/N8/T5 ; acceptation arithmétique conditionnelle de90s ; égalité stricte au TTL rejetée ; calcul distinct par autorité/Worker/voie |
| C-NEG | Échantillon synthétique dépassant une borne fixe rejeté, même si on ajoute cet échantillon à la liste de calibration ; nombre d’appels/reprises excessif rejeté ; ancienne échéance dépassée ou incarnation remplacée rejetée |
| C-OP | Chaque opération complète <T, y compris le retour/nettoyage ; borne δ respectée ; toutes les sorties qualifiées, sans perdre un appel en cours du journal de campagne |
| C-SERVICE | Chaque chemin due initiale→résolution physique→nettoyage <C(N,R) préfigé ; attente de connexion, tours partiels, polling et revalidation inclus ; pas de remise à zéro de due après omission |
| C-JITTER | Mesurer le retard cumulé pertinent par cycle, pas seulement `maxGap` ; rapprocher les intervalles de lancement/retour locaux des appels et les attentes prévues. Heartbeat/GC restent diagnostics complémentaires ; J n’est pas recalculé à partir des observations |
| C-PG | Pour chaque ancien `(C,p,ownerToken)` et `(C,id,claimToken)`, extension durable, même token, ancienne échéance encore future d’au moins S au constat extérieur post-commit ; toutes les12 leases et tous les claims démarrés retenus contrôlés, à chaque cycle |
| C-LOAD | Préparations RO réellement retenues et PID/XID extérieurs ; M_C/M_W ; quatre pools par Worker ;40/64 connexions de rôles en W2 ; serveur100 avec16 réservées ; charge/connexions externes consignées |

Pour C-PG, lire le vecteur échéances/tokens et `clock_timestamp()` dans une observation cohérente par autorité, depuis une connexion extérieure identifiée ; une lecture RC des nouvelles échéances prouve leur visibilité après commit. Exiger `new_until > old_until` et `observed_db_now + S < old_until`. Cette seconde condition est volontairement plus forte qu’un contrôle au seul commit. Conserver les instantanés initiaux et de chaque cycle ; pour le cycle suivant, utiliser l’échéance réellement étendue. Ne pas soustraire une horloge murale JVM à une échéance PostgreSQL et ne pas supposer une horloge commune WRITE/READ.

Le certificat concerne l’enveloppe de charge, de concurrence et de fautes déclarée. Il n’est pas une garantie universelle de latence ni une qualification de production. Un dépassement d’objectif sous conditions admissibles est FAIL C. Une observation manquante, un run interrompu ou une panne extérieure démontrée hors enveloppe est INCONCLUSIVE, avec motif et données conservés ; jamais PASS par exclusion rétroactive d’un mauvais échantillon.

## 4. AUD-I5-02 — cause racine et frontière du tour

`V3DiscoveryLoop.runOnePass()` conserve bien `RequestProgress.next()` après retour certain, mais ignore `tourFinished`. `ConsumptionObservation.Phase.DISCOVERY_TOUR` existe déjà. `MicrometerV3ConsumptionObservation.record()` sait déjà le convertir en compteur `pocoma.v3.rotation.tours`, labels bornés `consumption` et `loop=discovery`. Les tests m01/m02 vérifient d’autres frontières et l’omission d’un start, sans exiger une fin de tour Discovery réelle. Le défaut est donc une émission absente et un oracle incomplet.

Un **tour Discovery burnable** est la navigation volatile d’un couple `(Consumption, partition)` à borne UUID supérieure U figée. Le contrat I4 le termine seulement après retour normal de l’unité protégée : absence de frontière CAPACITY et soit page vide, soit préfixe franchi atteignant U ; `next` redevient alors `RequestWalk.fresh()` et `tourFinished=true`. Une page vide d’une source vide compte comme tour vide confirmé. Un dernier passage peut terminer un tour avec zéro nouvelle admission ; le booléen de travail retourné par `runOnePass()` ne décide donc pas du compteur.

Ce tour ne prouve ni couverture durable de toutes les demandes PENDING, ni traitement/terminalisation des sources. SKIP LOCKED, filtrage de partition et mutations concurrentes gardent les limites I4. Un retour CAPACITY, un lease indisponible, BUSY_OR_NOT_OWNER, une exception ou un résultat perdu ne terminent pas un tour. Une source qui se vide entre deux pages peut légitimement terminer le tour au retour vide confirmé.

Une **opportunité** est un appel borné sur un couple C/p ; sa fin n’est pas une fin de tour. Une rotation des C/p n’en est pas une non plus. Les Discovery non-burnables avancent K par fenêtres d’événements ; `EventProgress` n’expose pas de tour à U figée. Elles ne produiront pas artificiellement `DISCOVERY_TOUR`. Le compteur représente ici les tours de navigation burnables ; les fenêtres/rotations non-burnables restent observées par les métriques existantes. Étendre la notion aux événements exigerait un autre contrat, hors de ce correctif.

### Correction productive unique

Dans la branche burnable de `V3DiscoveryLoop`, **après** le retour normal de `discoverRequests` et la conservation de `result.next()`, émettre une fois `worker.event(binding, DISCOVERY_TOUR, CONFIRMED, 1, Duration.ZERO)` si `result.tourFinished()` est vrai. Aucune émission dans `finally`, au wrap du round-robin, sur `admitted>0`, sur `examined`, sur une page préparatoire ni sur le seul statut COMMITTED.

Réutiliser `worker.event` et `ConsumptionObservation.safely` : pas de SQL, nouvelle table, nouveau timer de tour, label d’UUID/partition, ou nouvelle collection pour comptabiliser les tours. Conserver les signatures `RequestProgress`, `ConsumptionObservation` et l’adaptateur Micrometer. Une panne de l’observer reste sans effet transactionnel.

### Nouveaux oracles Discovery

| Cas | Delta du compteur de tours et oracle indépendant |
|---|---|
| Tour à plusieurs pages | Fixture de clés UUID ordonnées, U connue, page size réduite ;0 sur pages intermédiaires,1 au retour qui franchit U ; comptage exact malgré rotations vers d’autres couples |
| Tour sans nouvelle admission | Clés déjà enrôlées ou hors partition ;1 seulement à la vraie fin, même avec `committedAdmissions=0` |
| Source vide / disparition de la fin de source |1 au retour vide confirmé protégé ; aucune émission sur la lecture préparatoire seule |
| Saturation | CAPACITY initiale ou après préfixe :0 ; après restitution de capacité, reprise du préfixe I4 puis1 à la vraie fin ; saturation M_W seule ne bloque pas Discovery |
| Ownership absent | Acquire sans lease :0 ; protection BUSY_OR_NOT_OWNER, y compris page préparée vide/finale :0 |
| Exceptions | Source, admission, fence, rollback confirmé, UNKNOWN et COMMITTED sans résultat exploitable :0 ; la résolution physique seule ne reconstruit pas `tourFinished` |
| Non-burnable | Fenêtre Event committée/vidée et checkpoint progressé :0 tour burnable ; autres métriques présentes |
| Observer fautif | Exception de métrique absorbée ; même walk/progrès/protocole/issue que le contrôle positif |
| Oracle négatif | Signal de tour volontairement omis ou injecté sur une page intermédiaire : le checker doit rejeter le décompte, même si une série Micrometer existe |

L’oracle déterministe utilise la liste de clés et l’ordre d’appels préparés par la fixture, ainsi qu’un journal séparé du retour de l’owner transactionnel ; il ne calcule pas son attendu en comptant les `DISCOVERY_TOUR` qu’il reçoit. Le test PostgreSQL fait passer la vraie boucle et le vrai `DiscoveryEngine`/owner/adapter, avec étapes contrôlées avant/après pages et constat SQL extérieur des slots/ownership/commits. Il compare le delta du registre Micrometer au scénario attendu : la seule invocation directe de `record(DISCOVERY_TOUR)` serait insuffisante. Les cas UNKNOWN sont unitaires dans ce correctif ; aucune nouvelle coupure TCP ni reproduction F03/F04 n’est nécessaire pour tester cette condition d’émission.

Le lease90s peut prolonger l’attente d’un takeover après crash jusqu’à l’échéance durable correspondante. Ce coût de disponibilité doit être accepté avec l’enveloppe ; les anciens essais de takeover ne certifient pas un délai identique sous cet override. La cadence10s, les emprunteurs et les connexions restent identiques : A/B sont réutilisables sous contrôle du diff, sans nouvelle affirmation de latence de reprise.

## 5. Verification scope prospectif

| Champ normatif | Déclaration pour le correctif, après validation |
|---|---|
| Expected production impact | Une émission d’observation dans POLL ; aucun autre comportement productif attendu |
| Primary slice | COMMAND — `runtime-command-consumption-worker` |
| Secondary canonical slices | NONE ; aucun comportement V3 métier EVENT/PROJECTION/BINDING/LKV/WEB modifié |
| Complément de consommation partagé | MONOLITH — `runtime-monolith`, seconde racine utilisant la même boucle ; composer les deux ancres dans une exécution pour éviter de retester leurs dépendances communes |
| Required local proof | Oracles déterministes de tour et budget ; nouvelles validations r05/r12 ; intégration métrique réelle ; gate composé sans filtre |
| Forbidden production impact | I2/I3/I4, DDL/migrations, owners/TM, stores SQL, quatre pools et tailles, WorkerCapacity/M_C/M_W, fencing, sélection/revalidation/renewals, maintenance et drainage ; autres runtimes, domaines et fonctionnalités différées |
| Global architecture gate | NOT REQUIRED : ni arête Maven, ni module, ni frontière, ni règle d’isolation modifiée ; pas de nouvelle dépendance Micrometer dans POLL |
| Full reactor | NOT REQUIRED pour ce correctif borné ; aucun nouveau jalon majeur ni intégration main |
| Database verification | CURRENT V3, mêmes migrations/fixtures jetables WRITE/READ, image PostgreSQL17.11 épinglée ; pas de certification HISTORICAL/BASELINE |
| Next mandatory global gate | Intégration main ou prochain jalon majeur L2/I6/I7/G3 selon son plan ; architecture et full restent alors applicables |
| Escalation conditions | Besoin de changer default/profil productif, contrat Java/SQL, renewal, pool, POM ou root wiring : documenter la contradiction et réviser ce scope avant code ; ne pas élargir silencieusement |

L’ajustement TTL est limité au contrat de certification, aux fixtures et à l’override documenté existant. Modifier les valeurs productives par défaut demanderait un scope amendé ; ce n’est pas nécessaire à la proposition de certificat90s.

**Articulation avec le plan initial I5 :** ses §§24/30 demandent les cinq gates au gel final du premier runtime actif. Elles ont été exécutées pour ce jalon. Le présent addendum propose, pour les deux findings seulement, la gate composée ci-dessous avec dossier de delta, sans réattribuer les anciens PASS au nouveau SHA. Sa validation doit approuver cette sélection explicitement. Sans cet amendement, prétendre respecter la prescription initiale « toutes gates finales au même gel » tout en ne les rejouant pas serait inexact. Les archives initiales et leurs statuts ne sont pas réécrits.

## 6. Fichiers à modifier après validation

Chemins relatifs à la racine du dépôt. Les CREATE sont proposés, pas créés à cette étape.

| Action | Fichier | Modification limitée |
|---|---|---|
| MODIFY production | `app/orchestrator-poll-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/poll/consumption/v3/V3DiscoveryLoop.java` | Émission conditionnée à `tourFinished` après retour confirmé |
| MODIFY test | `app/orchestrator-poll-consumption/src/test/java/com/kartaguez/pocoma/orchestrator/poll/consumption/v3/V3ConsumptionWorkerTest.java` | Ajouter oracle local de tour, injection d’observer dans la fixture ; préserver u01…u10 |
| MODIFY test | `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3WorkerResourcesPostgresTest.java` | r05/r12 : contrat indépendant, durée90s explicite, deux cycles et journal par Worker/autorité/voie/incarnation, oracles échéances/tokens ; helpers test locaux seulement |
| CREATE test | `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/I5TemporalBudgetTest.java` | Contrat test immuable partagé avec r05/r12 ; arithmétique, cas30s rejeté, négatifs de dépassement ; aucune dépendance supplémentaire |
| MODIFY test | `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3WorkerMetricsTest.java` | Ajouter m03 : vraie fin de tour→registre ; saturation/ownership/rollback ; checker négatif omission/émission anticipée ; garder m01/m02 |
| MODIFY documentation courante | `docs/v3/L2_I5_Worker_Development_Runbook.md` | Sémantique du compteur et override90s qualifiable, défaut30s non certifié, limites de l’enveloppe |
| CREATE documentation/preuve | `docs/v3/L2_I5_Audit_Remediation_Proof_Manifest.json` | Allocation additionnelle exacte, contrat temporel figé, oracles, variantes, références héritées avec SHA/hash et statut REUSED séparé de FRESH |
| CREATE documentation/preuve | `docs/v3/L2_I5_Audit_Remediation_Report.md` | Résultats futurs par finding/autorité, coûts réels, scope/diff et décision d’audit ; C et réserves restent ouverts avant preuve |
| CREATE preuve | `docs/testing/evidence/L2-I5-audit-remediation/` | Contrat avant run, reçus, XML frais, vecteurs DB, journaux, registre/scrape et empreintes ; aucune copie présentée comme réexécution |

**REUSE UNCHANGED :** `DiscoveryEngine.java` et `RequestProgress`, `ConsumptionObservation.java`, `MicrometerV3ConsumptionObservation.java`, les services renewal/Worker/Engine I3, `V3ConsumptionWorkerSettings.java`, `LifecycleSettings.java`, POM, profils productifs, sources I2/I3/I4, migrations et manifestes/archives historiques I2/I3/I4/I5. Le présent plan est le seul fichier créé avant validation.

Le collecteur historique `verify-v3-l2-i5.py` impose C/R/M/A/F et tous leurs anciens variants ; il ne doit pas être invoqué comme collecteur du dossier borné, puis voir ses erreurs ignorées. Utiliser ses contrôles réutilisables de sources/méthodes/empreintes, avec inventaire additionnel propre et reçu de la commande composée. Aucun nouveau framework de collecte n’est requis. Si un script dédié s’avère nécessaire pour automatiser le reçu, le déclarer avant implémentation et lui ajouter des négatifs missing/skip/stale ; ne pas modifier les reçus historiques pour le satisfaire.

## 7. Matrice minimale de tests et preuves

| Catégorie | Exécution / réutilisation | Justification et coût |
|---|---|---|
| Ciblé obligatoire sans DB | Nouvel oracle de tour (COMMAND et IDENTITY_REQUEST au contrat générique, pas activation Identity métier), cas négatifs ; `I5TemporalBudgetTest` | Attraper surcoût, circularité, émission prématurée/omise et incompatibilité30s avant de payer PostgreSQL. Estimation30–90s build chaud, pas durée garantie |
| PostgreSQL obligatoire C | r05 : B32/MW1 WRITE retenu, B32/MW1 READ retenu, B32/MW4 mixte, B1/MW4 mixte ; r12 : W2 × B1/B32 × MW1/4 | Huit variantes nouvelles, pas de nouvelle dimension métier. Deux cycles/variante, nominal puis omission transitoire bornée. Même exécution observe WRITE/READ, pas deux campagnes identiques |
| PostgreSQL obligatoire métrique | Nouveau m03 dans la classe de métriques existante | Tour multipage contrôlé, métrique réelle, saturation et ownership négatifs ; rollback réel local. Coût prévisionnel10–30s supplémentaires, sans transport fautif |
| Gate de régression requise | Une commande composée COMMAND + MONOLITH sans filtre, sur le correctif gelé | Politique §§11/14 : la boucle partagée a deux racines actives ; fermeture Maven commune exécutée une seule fois. Inclut les nouvelles preuves PG, pas besoin de les rejouer d’abord en campagne ciblée verte |
| Déjà qualifié et réutilisable | A/B WRITE/READ ; r10 rotation lente, r11 omission F05 ; w02 compétition/takeover ; I2/I3/I4 et F01–F05 | Inventaire/owners/SQL/pools inchangés ; références et empreintes préservées. Aucun nouveau négatif F01–F05 ni campagne A/B autonome. La gate canonique rejouera certains tests positifs hérités : coût imposé par sa fermeture, pas nouvelle reproduction du finding |
| Documentaire obligatoire | Diff productif limité à l’émission ; empreintes des implémentations/protocoles réutilisés ; contrat préfigé ; données C par autorité ; deltas de métriques/attendus ; reçu de gate et correspondance des méthodes/variantes | Interdire transfert de PASS, omission d’échantillon, confusion30/90s, fausse fin de tour, preuve périmée ou skip requis |
| Diagnostics facultatifs | Calibration séparée si cause d’échec inconnue ; GC/heartbeat détaillé ; scrape DEV additionnel ; essai de latence artificielle | Seulement pour expliquer un échec ou une hypothèse. Sans verdict de certification et sans retoucher les limites de la campagne qui vient d’échouer |
| Non requis pour ce delta | Gate READ JDBC isolée, architecture globale, full reactor, campagnes historiques de migration, matrice complète F01–F05 | Aucun changement de ces capacités/frontières. Les anciens reçus restent historiques ; aucune affirmation de PASS frais à leur SHA |

Les hypothèses de validation sont préfixées pour tous les essais. Une évolution nécessaire de watchdog/barrière de test doit découler de ce budget et du nombre de cycles, être fixée avant run et rester distincte des seuils d’acceptation ; elle n’allonge jamais un lease ni ne prouve une fin physique. Aucun watchdog15s hérité ne doit tronquer une fenêtre théorique de plus de40s. Aucun choix de configuration après observation n’est accepté.

Commandes proposées, depuis `app/`, **NON EXÉCUTÉES** :

```sh
# Premier retour déterministe, aucun scénario PostgreSQL sélectionné.
./mvnw -pl runtime-command-consumption-worker -am \
  -Dtest=I5TemporalBudgetTest,V3ConsumptionWorkerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

# Unique gate de clôture du delta ; inclut r05/r12 et m03 frais.
./mvnw -pl runtime-command-consumption-worker,runtime-monolith -am test
```

Le premier filtre sélectionne des unitaires dans des modules différents ; le contrôle du reçu doit exiger les deux classes et leurs nouvelles méthodes PASS, zéro skip, plutôt que se fier au seul code de sortie Maven. Pour la gate composée : XML et variants physiques frais, propriétés effectives/contrat/hash, sources/classes, commande/cwd, environnement, timestamps, résultats et erreurs conservés. `git diff --check` et le contrôle documentaire des allocations/liens complètent le dossier.

## 8. Coûts et optimisation justifiée

Mesures historiques lues dans les XML de F, et non réexécutées : r05=57,286s ; r12=87,561s, soit **144,847s** pour leurs huit variantes à un cycle. r10=26,678s ; r11=9,487s ; m01/m02=8,609s/3,285s ; dix unitaires Worker : suite0,386s. Ces coûts excluent une partie du démarrage Maven/conteneurs.

Prévision du correctif : un cycle supplémentaire de cadence10s pour chacune des huit variantes ajoute environ80s de temps de mesure, plus injection/observation/nettoyage ; **4–7min** pour les preuves C nouvelles, et10–30s pour m03. Pas de calibration additionnelle prévue, pas de répétitions jusqu’à obtention d’un vert. Ces durées sont des enveloppes de planification sur le même banc, pas des SLO ni des délais d’acceptation.

Le reçu historique donne C=3813,044s (63,55min), R=41,796s, M=1991,647s (33,19min), A=3933,894s et F=3933,802s : **228,57min cumulées** pour les cinq gates. Elles partagent de nombreuses régressions. L’inspection des XML C/M trouve247/220 suites,217 communes ; les trois suites exclusives MONOLITH représentent7 tests et38,562s dans M. Une gate composée évite donc de payer deux fois les tests amont, sans les exclure.

Budget prévisionnel global : **environ65–75min pour une gate composée**, incluant les preuves C/métriques nouvelles, plus le ciblage unitaire court et les contrôles documentaires. Ce budget est estimé à partir du coût C et du complément M, avec marge de build/environnement ; il n’a pas été mesuré. Les 4–7min C sont comprises dans cette gate, pas ajoutées une seconde fois. L’économie attendue par rapport aux cinq gates est d’environ154–164min cumulées. La simultanéité des anciennes gates rend leur temps mural différent de cette somme.

Une gate canonique sans filtre rejoue nécessairement les tests A/B et certaines régressions F01–F05 de sa fermeture. C’est le minimum imposé ici par la politique de slice ; les supprimer ou introduire des exclusions Surefire pour gagner ce coût serait une modification du protocole de vérification. En revanche, **aucune** nouvelle campagne autonome A/B, aucun reproducer négatif F01–F05 et aucun second passage du full n’est justifié par ces deux findings. Les migrations historiques éventuellement exécutées par les tests amont sont déclarées comme coût de fermeture, pas comme une nouvelle campagne HISTORICAL. V3 CURRENT n’est pas présenté comme une baseline V2 TRUSTED.

## 9. Préservation des preuves et conditions de clôture

Les archives, reçus, JSON A/B/C et diagnostics historiques restent byte-identiques. Le manifeste additionnel référence les preuves réutilisées à leur SHA d’origine et marque C historique **insuffisant pour lever AUD-I5-01**, malgré son ancien résultat local. La preuve fraîche s’ajoute ; elle ne réécrit pas les faits anciens. Préserver également les échecs/reproductions F01–F05 et les manifestes hérités.

**AUD-I5-01 est fermable seulement si :**

1. le contrat temporel, son calcul par voie, δ, les lots partiels, les reprises et le périmètre30/90s sont approuvés et figés avant validation ; aucune observation de validation n’influence son seuil ;
2. les huit variantes nouvelles passent C-OP/SERVICE/JITTER/PG/LOAD sur WRITE et READ, sans skip ni token changé, avec les durées effectives concordantes ; les négatifs C-PRE/C-NEG rejettent les contre-exemples ;
3. la borne de nombre d’appels/reprises et les hypothèses de charge sont établies pour les cycles revendiqués ; les omissions/UNKNOWN hors enveloppe ne sont pas silencieusement inclus ;
4. le dossier relie sources, configuration, résultats frais et preuves A/B réutilisées ; l’audit indépendant accepte la portée du certificat, en particulier l’absence de certificat30s dans cette option.

**AUD-I5-02 est fermable seulement si :**

1. la vraie boucle émet exactement un événement par fin de tour burnable confirmée et zéro dans les cas négatifs décrits ;
2. le registre Micrometer augmente de la quantité attendue à ces seules frontières ; les checkers rejettent omission et émission prématurée ; les métriques gardent leurs labels bornés et n’effectuent aucun SQL ;
3. le correctif conserve `next`, les issues transactionnelles, les invariants d’ownership et l’isolation d’une panne de l’observer ; le test réel et les unitaires passent ;
4. la gate composée et la revue du diff sont acceptées, avec une décision indépendante explicite sur le finding.

**R-AUD-I3-03 reste OPEN tant que C n’a pas passé et n’a pas été accepté indépendamment.** A/B ne la ferment pas et DRAINING ne vaut pas respect d’échéance. Après C, sa clôture reste soumise à A∧B∧C sur les deux autorités et à la qualification de l’enveloppe de déploiement effectivement revendiquée ; ce plan ne la ferme pas automatiquement. Aucun PASS global L2 n’en découle.

Risques résiduels : défaut30s toujours non certifié par ce modèle ; limites explicites du scénario R=1 ; pauses JVM/transport/DDL durables hors garantie ; δ ou retour/nettoyage non bornés pouvant invalider T ; coût effectif de l’instrumentation à inclure dans la validation ; métrique locale volatile pouvant être perdue au crash ou sur observer défaillant ; fenêtres Event hors sémantique du compteur burnable. Une contradiction nouvelle impose une révision motivée, sans élargissement silencieux ni nouveau mécanisme I5.

## 10. État de cette étape de planification

- SHA de départ et de fin : `c6f3de1a482a7e67cf91e2fa9554d49e2b23270d`, branche `feat/v3-architecture`, worktree `/private/tmp/pocoma-v3-postaud`.
- Slices exécutées : NONE ; slices prospectives : COMMAND primaire, MONOLITH complément, secondaires canoniques NONE.
- Commandes effectivement utilisées : lectures `cat/sed/rg/nl`, `git status/branch/rev-parse/show/worktree/diff`, analyses Python standard de JSON/XML/tar et calculs de coûts ; création et contrôle documentaire de ce seul plan.
- Maven/PostgreSQL/Docker : aucune exécution. DB présente : NONE. Aucun reproducer F01–F05, gate globale ou full reactor exécuté ; ils ne sont pas requis pour préparer ce plan.
- Franchissement de slice inattendu : NONE. Code, tests, scripts et preuves historiques inchangés ; aucun commit ni push.
- Findings : toujours OPEN / clôture I5 FAIL selon l’audit communiqué, jusqu’à correction validée, preuves nouvelles et décision indépendante.
