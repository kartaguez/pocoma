# AUD-I5-01 — contrat C/B v2, justification préalable

Mandat intégré du 29 octobre 2026. Base 52d8eea94f77029727c3e57ba29894e8fc519dce.
Le contrat v1 et son FAIL R1 restent historiques, inchangés. Les actuels tests
r05/r12 sont remplacés par les deux frontières v2 ; aucun résultat v1 n'est requalifié.

## Périmètre de vérification

COMMAND primaire ; MONOLITH complément de gate, aucune autre slice canonique.
Impact productif attendu/interdit : zéro changement productif, de port, owner,
DDL, pool, scheduler, migration, dépendance ou frontière. Preuve locale : tests
Java déterministes du vrai sélecteur et des checkers, puis PostgreSQL ciblé
r05/r12. Si PASS, une seule commande sans filtre depuis app/ :
`./mvnw -pl runtime-command-consumption-worker,runtime-monolith -am test`.
Architecture globale : NOT REQUIRED. FULL : NOT REQUIRED et NOT RUN.
DB : CURRENT V3, fixtures existantes ; pas de campagne historique autonome.
Toute impossibilité normative exigeant un changement productif arrête le mandat.

## Propriété et preuve analytique

C : omission résolue => suspension de tout nouvel usage ; seule une réponse CAS
positive du même token réactive ; fenêtre locale expirée => retrait local ; UNKNOWN
=> exclusion et drainage sans présumer la résolution physique. Ces états ne sont
jamais des preuves d'extension. Les preuves A/B et F01–F05 historiques restent
réutilisées uniquement pour leur comportement inchangé, sans nouveau certificat.

B : pour une incarnation éligible conservée, chaque prochaine tentative commence
et se termine au plus tard D(N)=N*(T+q)+P+J après son éligibilité. Une opération
en cours compte parmi les N. À l'éligibilité t, renewedAt<=t-I. Tout candidat servi
après t reçoit soit un timestamp courant (succès), soit start-I+q (omission),
strictement supérieur à celui du candidat en attente. Il ne peut donc le dépasser
une seconde fois. N est le nombre maximal d'entrées de cette autorité, pas le
nombre de lots présumés pleins. Des insertions concurrentes ont un timestamp
plus récent. Les liens d'égalité peuvent consommer N-1 places, pas davantage.
Le nombre total d'omissions n'est pas borné. Cette preuve suppose des timestamps
monotones sans overflow, un seul appel physique actif par voie, et aucun UNKNOWN.

D est également un majorant conservateur retour d'omission -> fin de prochaine
tentative : la rééligibilité à start+q peut précéder le retour ; le terme P couvre
une attente inactive éventuelle. Sans disparition, arrêt ou perte d'incarnation,
l'algorithme réessaie ; il ne promet pas une extension sur une ligne verrouillée.

L'enveloppe temporelle B impose T=250ms pour une unité complète (emprunt, début,
SQL, commit/rollback, retour et nettoyage), P=1s, q=10ms, J=1s cumulé sur les
intervalles sans travail. C'est une **hypothèse expérimentale conditionnelle**,
pas un timeout garanti par le runtime. Justification avant mesures : Nmax=8 et
fenêtre locale5s imposent T<(5-1-1-8*.01)/8=365ms ; 250ms réserve 920ms de marge.
Le timeout productif plus large ne prouve pas cette hypothèse. Tout dépassement
observé dans la qualification positive invalide l'enveloppe et arrête la campagne,
sans recalibration, exclusion ni rerun. L'hypothèse reste explicitement exigée
pour toute utilisation future du certificat. Le budget historique T=5s ne suffit
pas pour garantir une revalidation ; il n'est pas remplacé rétroactivement.

| Autorité | N W1/W2 | D W1/W2 | delta+I+D+S W1/W2 |
|---|---|---|---|
| WRITE leases |4/2|3.04s/2.52s|20.04s/19.52s|
| READ leases |8/4|4.08s/3.04s|21.08s/20.04s|
| claims, borne conservatrice singleton |4/4|3.04s|20.04s <60s|

Dmax4.08s<5s ; delta5s+I10s+Dmax+S2s<90s, strictement. La certification est
exclusivement expérimentale90s. La configuration30s reste NON CERTIFIÉE,
même si une sous-inégalité v2 est arithmétiquement favorable.

## Disponibilité physique et fenêtres

La disponibilité n'est jamais définie par la liste des renewals retournés.
Une fenêtre candidate est fixée par l'entrée/sortie du store de ownership dans
une transaction de renewal, avant lecture de son résultat. Pour chaque ligne,
le checker exclut toute fenêtre chevauchant la durée **entière** d'une autre
transaction susceptible de verrouiller cette ligne. Toutes les transactions des
owners Control Engine et Discovery sont journalisées du début de l'appel owner
jusqu'après son retour/nettoyage, avec PID/XID obtenus dans l'unité physique et
clés capturées avant chaque appel ownership acquire/protect/renew. Les autres
Workers sont inclus. Les déteneurs injectés ont une connexion séparée, un PID,
un XID, un verrou FOR UPDATE acquis et une libération COMMIT observée séparément.
Toute transaction encore active, dont les futures clés sont inconnues, est
conservativement conflictuelle pour toutes les lignes de son autorité.
Les préparations Business restent RO ; aucun autre écrivain ou administrateur
n'est admis pendant la campagne. Les sessions extérieures sont inventoriées.
Un chevauchement est classé conservativement OBSTRUCTED, même si le lock réel
était plus bref ; aucune absence de lock n'est inférée d'un simple échantillon.

B impose une fenêtre physique disponible pour chaque ligne, couvrant une
tentative et son opération avant le retryUntil original. La borne D porte sur
chaque prochaine tentative, jamais sur le nombre total des omissions. Cette
condition d'environnement est plus forte qu'une disponibilité intermittente
arbitraire ; les disponibilités tombant exclusivement entre les tentatives ne
suffisent pas. Une fenêtre continue de longueur D+T après rééligibilité est
une condition suffisante indépendante de la phase des tentatives. Les tests
qualifient les fenêtres réelles par couverture exhaustive des transactions,
pas par une extrapolation de samples pg_stat_activity. Une fenêtre disponible
sans extension même token avant l'ancienne échéance est un FAIL, jamais un drainage
réussi. Si la couverture des transactions est incomplète : INCONCLUSIVE.

Les oracles restent séparés : rang/prochaine tentative ; unité physique T ;
gaps cumulés J ; fenêtre libre/obstruée ; retryUntil local ; vecteur PostgreSQL
extérieur token/owned_until/clock_timestamp avant/après commit ; sécurité de
suspension/retrait. Les négatifs rejettent mauvais token, absence/retard
extension, faux témoin libre avec chevauchement, journal incomplet, dépassement
T/D/J et réutilisation pendant suspension. Aucun seuil n'est tiré des résultats.

## Frontières et ordre

W1/B1/MW4 maximise N et le nombre de petits lots ; W2/B32/MW4 ajoute
coexistence de deux Workers, pools64, READ/WRITE et lots partiels. Le vrai
sélecteur est testé avec omissions répétées, timestamps égaux et ancienneté
adverse. B32 ne peut augmenter le nombre de places devant un candidat ; MW1
réduit la contention Business et ne modifie pas le sélecteur leases. Ces deux
frontières couvrent le mécanisme et les risques ; elles ne certifient pas des
charges, concentrations, pauses JVM ou populations supérieures.

Chaque frontière exécute nominal WRITE/READ, puis contention admissible avec
libération au retour de la première omission et sans borne R, puis contention
excessive >5s avec suspension/retrait, token durable non prolongé et absence de
nouvel usage. Arrêt immédiat au premier contre-exemple positif. Le négatif
excessif viole volontairement la disponibilité et teste C ; il ne doit pas être
compté comme une certification B. Preuves compactes seulement ; logs locaux
hors dépôt, empreintes et reçu exacts versionnés. Gel contractuel et applicatif
avant PostgreSQL ; toute modification ultérieure de code exige un nouveau gel
et interdit d'attribuer un PASS à un code différent.
