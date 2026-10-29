# I5 — résolution temporelle intégrée v2 : FAIL de qualification

**Verdict : FAIL de la qualification v2.** W1/B1/MW4 passe ; W2/B32/MW4
échoue dès son nominal sur un témoin de disponibilité physique insuffisant.
La propriété d'extension conditionnelle n'est donc pas certifiée. Ce résultat
ne démontre ni expiration, ni changement de token, ni défaut du fencing.
AUD-I5-01, R-AUD-I3-03 et I5 restent **OPEN**, jusqu'à acceptation par audit
indépendant. Aucun nouveau GO demandé pendant cette intervention.

## Provenance, périmètre et gel

Dépôt `kartaguez/pocoma`, branche `feat/v3-architecture`, baseline locale et
distante vérifiée : `52d8eea94f77029727c3e57ba29894e8fc519dce`.
Worktree propre réutilisé : `/private/tmp/pocoma-v3-postaud`. L'inventaire Git
initial incluait les autres worktrees ; le répertoire principal est resté sur
`feat/runtime-monolith`, avec ses modifications préexistantes préservées.
Aucun reset, clean, stash, force push ou écrasement de travaux étrangers.

Gel contractuel et applicatif testé :
**`5eab2e94379ef40c3f9ff54c14a4d04a8f13dcbd`**. Le commit de livraison
suivant ne contient que rapport et preuves ; son SHA est donné dans la livraison
Git, sans réattribuer de PASS à du code différent. Les 1227 entrées applicatives
et de qualification hashées restent identiques pendant les deux ciblages.

Périmètre déclaré avant implémentation : **COMMAND primaire**, **MONOLITH**
complément de gate ; aucune autre slice canonique. Impact productif attendu et
observé : **zéro**. Aucun POM, port, owner, table, migration, pool, scheduler,
frontière, renewal ou drainage productif changé. Franchissement inattendu :
**NONE**. Architecture globale : **NOT REQUIRED / NOT RUN**. Full reactor :
**NOT REQUIRED / NOT RUN**, puisqu'il n'y a ni clôture de Wave, ni intégration
main, ni changement structurel. DB : **CURRENT V3**, fixtures existantes ; aucune
campagne historique ou certification de baseline ajoutée. PostgreSQL est épinglé
à `postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73`.

## Contrat retenu et justification

L'[addendum préalable](L2_I5_Temporal_V2_Addendum.md) définit séparément C
(suspension, retrait local borné, exclusion UNKNOWN et drainage sûr) et B
(extension conditionnelle de la même incarnation avant son ancienne échéance).
Un drainage ou un retrait local ne vaut jamais extension. R=1 n'est plus une
obligation centrale ; les omissions et tentatives sont toutes journalisées.
Le contrat v1, son FAIL et le contre-exemple R1 restent byte-identiques.

Nouveau [contrat I5-CB-90-v2](../testing/evidence/L2-I5-temporal-v2/temporal-contract-v2.properties),
figé avant PostgreSQL, SHA-256 :
`b37ccf05c97813ad135b25643c78bad8a1d0893db75eaf3ed9e9778edc82d478`.

La prochaine tentative est bornée par **D(N)=N×(T+q)+P+J**. Un candidat
antérieur, servi après l'éligibilité du candidat étudié, reçoit un renewedAt
plus récent, y compris après omission : il ne repasse pas devant une seconde
fois. Au plus N places sont nécessaires ; aucun lot plein n'est présumé.
La borne suppose l'absence d'UNKNOWN, des pauses bornées et une incarnation
encore présente. Le test déterministe appelle le vrai service, avec 2/4/8
candidats, omissions répétées et égalités d'ancienneté.

| Borne conditionnelle préalable | WRITE | READ |
|---|---:|---:|
| W1, N |4|8|
| W1, D |3,04 s|4,08 s|
| W2, N |2|4|
| W2, D |2,52 s|3,04 s|

T=250 ms pour l'opération complète, P=1 s, q=10 ms, J=1 s cumulé.
**250 ms est une hypothèse expérimentale**, pas une garantie du timeout
productif. Elle est choisie avant mesure à partir de la contrainte analytique
T<365 ms pour huit candidats et la fenêtre de cinq secondes. Le majorant
historique de cinq secondes par opération ne satisfait pas cette contrainte.
Le maximum D=4,08 s laisse 920 ms dans la fenêtre locale de cinq secondes.
Le checker utilise le **retryUntil original**, capturé indépendamment du
résultat, sans le remettre à zéro à chaque omission.

Avec δ=5 s, cadence I=10 s et marge S=2 s : δ+I+D+S≤21,08 s<90 s.
L'âge physique initial est contrôlé avec l'horloge PostgreSQL ; les durées et
les références locales utilisent l'horloge monotone. Les claims conservés ont
également des vecteurs SQL extérieurs d'extension avant leur ancienne échéance
60 s ; aucune nouvelle certification générale du sélecteur claims n'est déduite.
**Configuration expérimentale 90 s seulement ; 30 s NON CERTIFIÉE**, même si une
sous-inégalité arithmétique semble favorable.

## Disponibilité et oracles indépendants

Les owners Control Engine et Discovery existants sont enveloppés uniquement en
test : début d'appel, PID/XID dans la transaction réelle, clés touchées par
ownership, résolution et retour/nettoyage. Les autres Workers sont inclus.
Les préparations Business réellement retenues sont RO et observées par PID/XID
extérieur. Pools réels : 32 connexions de rôles en W1, 64 en W2 ; serveur 100,
réserve 16. Les détenteurs injectés utilisent une connexion séparée, un FOR UPDATE,
un PID/XID observé et un COMMIT explicite de libération.

La fenêtre candidate est l'entrée/sortie du store de renewal, choisie sans lire
son résultat. Tout chevauchement avec l'enveloppe entière d'une autre transaction
susceptible de verrouiller la ligne fait refuser le témoin libre. Une transaction
encore active est conservativement conflictuelle pour toutes les lignes possibles
de son autorité. Un journal incomplet ou une résolution physique inconnue ne
peut établir la disponibilité. Les vecteurs PostgreSQL extérieurs contrôlent
séparément token, owned_until/claimed_until, clock_timestamp et visibilité du
commit avant ancienne échéance avec marge 2 s.

Les négatifs déterministes rejettent dépassements T/D/J/fenêtre locale, changement
de token, absence d'extension, observation tardive, faux témoin libre et couverture
incomplète. Les expériences de contention excessive vérifient suspension/retrait
sans extension et sans déduire une perte durable de la seule suppression locale.
Les garanties plus larges de sûreté et UNKNOWN restent appuyées par les preuves
F01–F05 inchangées, sans campagne autonome supplémentaire.

## Scénarios exécutés et arrêt

| Scénario | WRITE | READ | Résultat |
|---|---|---|---|
| Déterministe, vrai sélecteur et négatifs | inclus | arithmétique N8 |15 tests PASS, zéro skip au gel|
| W1/B1/MW4 nominal | extension même token | extension même token |PASS|
| W1/B1/MW4 contention admissible | détenteur identifié puis libéré | idem |PASS, suspension puis revalidation|
| W1/B1/MW4 contention excessive | suspension puis retrait, échéance inchangée | idem |PASS de C ; disponibilité volontairement violée|
| W2/B32/MW4 nominal | vecteur extérieur valide | vecteur extérieur valide |FAIL du témoin physique pour une ligne READ|
| W2 contention admissible/excessive | NOT RUN | NOT RUN |arrêt au premier échec|
| Gate COMMAND + MONOLITH sans filtre | NOT RUN | NOT RUN |qualification préalable non passée|

W1 contrôle 12 leases et 4 claims retenus, W2 contrôle 12 leases et 8 claims
retenus. Dans W1, les détenteurs excessifs restent physiques au moins six
secondes après l'omission, au-delà du retrait local ; le token durable et
l'ancienne échéance restent inchangés jusqu'à leur libération. Le maximum
observé d'opération complète des phases positives W1 est 4,694500 ms ; il est
strictement descriptif et n'a fixé aucun seuil.

Les frontières couvrent le nombre maximal de petits lots/entrées W1 et la
coexistence de deux Workers avec lots partiels W2. La justification analytique
pour B32/MW1 figure dans l'addendum ; aucune variante non exécutée n'obtient un
PASS transféré. L'échec W2 interdit toute extrapolation de certification.

## Nouveau contre-exemple et limite de preuve

[Contre-exemple structuré](../testing/evidence/L2-I5-temporal-v2/counterexample.json)
`V2-W2-AVAILABILITY-WINDOW-01`, Worker 0, **READ**, clé
`a5000000-0000-4000-8000-000000000001/1` :

- fenêtre monotone du store : `[2456820926921625,2456820928618083]` ns ;
- Discovery, PID 250/XID 2160, transaction COMMITTED :
  `[2456820925265750,2456820927476541]` ns, même ligne ;
- renewal, PID 251/XID 2161, transaction COMMITTED ;
- token conservé : `0622a81d-90e3-48e4-8ddf-dc99d616e2f3` ;
- échéance retournée : `18:41:42.725924Z` → `18:41:53.678511Z`,
  le 29 octobre 2026 ; l'observation SQL extérieure avant l'ancienne échéance passe.

Le lot traite les partitions 0 puis 1. Le chevauchement des **enveloppes de
transactions** suffit à invalider le témoin suffisant du contrat v2 ; il ne
localise pas la tenue effective du row lock au moment exact du CAS de partition 1.
Le CAS peut donc réussir alors que ce checker conservateur refuse de certifier
la disponibilité. Le détenteur susceptible est cette fois identifié ; aucune
attribution au réseau, à une panne ou à une indisponibilité continue n'est inventée.

C'est un FAIL de qualification et une limite de la preuve physique, **pas un
contre-exemple démontré à l'extension conditionnelle du runtime**. Le résultat
de cette propriété reste non établi pour l'ensemble demandé. Il serait incorrect
d'assouplir après mesure la fenêtre au seul instant qui a réussi. Ni contrat,
ni seuil, ni code n'ont été retouchés après le gel ; aucun échantillon exclu,
aucun nouveau passage pour obtenir PASS. La gate reste NOT RUN.

## Commandes, coût et preuves

Depuis `app/` :

```sh
./mvnw -pl runtime-command-consumption-worker -am -Dtest=I5TemporalV2Test,V3ConsumptionWorkerTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl runtime-command-consumption-worker -am '-Dtest=V3WorkerResourcesPostgresTest#i5_r05' -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl runtime-command-consumption-worker -am '-Dtest=V3WorkerResourcesPostgresTest#i5_r12' -Dsurefire.failIfNoSpecifiedTests=false test
```

Lanceur depuis la racine :
`python3 scripts/testing/run-i5-temporal-v2.py /private/tmp/pocoma-i5-v2-qualification-5eab2e94`.
Il vérifie le gel propre, les entrées inchangées et les XML frais, puis s'arrête.
La commande composée prévue n'a **pas** été exécutée :
`./mvnw -pl runtime-command-consumption-worker,runtime-monolith -am test`.

Coût mural mesuré : **64,163 s W1 +30,479 s W2 =94,642 s** pour la qualification.
Six passages déterministes pendant la mise au point, tous réussis, totalisent
**41,624 s de temps Maven** ; le dernier vérifie le code du gel. Deux relances
administratives étaient inutiles après des erreurs de chemins dans les commandes
préparatoires ; aucune n'était une répétition de campagne PostgreSQL.
Total de durées comptabilisées : **136,266 s**, environ 2 min 16, hors surcoût de
lancement Maven des déterministes, lecture, conception, implémentation et rédaction.
Aucun coût de gate complète ajouté après le FAIL.

Le [manifeste compact](../testing/evidence/L2-I5-temporal-v2/qualification.json)
relie sources, SHA, classes compilées, commandes, statuts frais, coûts et fichiers.
L'[archive unique](../testing/evidence/L2-I5-temporal-v2/qualification-proof.tar.gz)
contient les journaux de transactions et tentatives, vecteurs, deux logs Maven,
XML frais, preuve déterministe finale et reçu complet des 1227 entrées.
Archive : 291456 octets, SHA-256
`fa510d8d477ce65dbf16c723c69c52c5569f711d0f078944da6a4c44084a7fe3`.
Les 115 fichiers historiques I5 correspondent à leurs empreintes ; A/B et F01–F05
restent réutilisables uniquement dans leur périmètre productif inchangé.

## Fichiers et publication

Nouveaux oracles : `I5TemporalV2Test.java`, `I5TemporalV2Postgres.java` et
`i5_v2_selector_bound` dans `V3ConsumptionWorkerTest.java`. r05/r12 dans
`V3WorkerResourcesPostgresTest.java` appellent les deux frontières v2 ; leurs
anciennes sources et preuves restent accessibles au commit de baseline.
Le lanceur `scripts/testing/run-i5-temporal-v2.py` assure l'ordre et l'arrêt.
Les autres changements sont cet addendum/rapport et le répertoire de preuves v2.

Le [diff exact baseline→gel](../testing/evidence/L2-I5-temporal-v2/frozen.diff)
est conservé ; SHA-256
`84a3ea766d434bdc2a1d17c0eebb5ae7f7174181204fe2ca4a8c168d413aa235`.
Le diff final est celui de `git diff 52d8eea94f77029727c3e57ba29894e8fc519dce <SHA-final>`.
Publication par commit puis push normal sur la branche mandatée ; SHA final,
synchronisation distante et état du worktree vérifiés dans la livraison finale.
