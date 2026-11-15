# L7-LB — correctif de reprise et fiabilisation des preuves

Mandat : `GO_L7_LB_START_RECOVERY_AND_EVIDENCE_FIX`.
Référence initiale : `b7b12c3da8b9fbca0d085110b13ffd1523c6dd7a`, branche
`feat/l7-lb-local-playground`. Les dix fichiers Bruno déjà modifiés au début
restent hors publication de ce correctif. Les preuves sont attribuées au
checkout local dirty ; elles ne certifient pas un checkout distant propre.

## Périmètre déclaré avant implémentation

LOCAL DISTRIBUTION : orchestration, observations, preuves et documentation.
Consommateurs WEB, COMMAND et PROJECTION exercés par les cinq JVM et les API
publiques ; aucune modification sous `app/`, des schémas, durées de lease,
fencing, découverte ou matérialisation. Niveau INTEGRATION distribution.
Gate globale architecture NOT REQUIRED ; réacteur complet NOT REQUIRED / NOT
RUN. Base CURRENT V3 pour la fixture neuve ; aucune migration historique V2.
Les JAR préexistants sont réutilisés après preuve d’équivalence des entrées
productives et de leurs hashes ; le SHA de build d’origine reste conservé.
Aucune campagne Maven n’est exécutée sous ce mandat.

## Problème et changement

Le run historique `1794676508-e55784d4` avait admis la commande
`8a22eb39-b241-4263-8910-da026287ebad` à 17:15:49.629748 UTC le 14 novembre.
La complétion WRITE est enregistrée à 17:19:08.612239 UTC, soit 198,982491 s
plus tard. Les workers recréés attendaient les baux anciens. Le contrôle placé
avant Compose ne couvrait que les workers déjà arrêtés. Le reçu d’échec
historique n’est ni modifié ni promu en succès.

Le nouveau parcours compare les vrais identifiants et dates des conteneurs,
puis attend la couverture effective des 36 couples participant/partition via
métriques directes et SQL READ ONLY. Budget de récupération 270 s distinct du
polling métier inchangé de 30 s. Une première observation complète termine
immédiatement la récupération. Les changements de génération pendant les
observations, le canari ou les contrôles finaux empêchent la qualification.

Les traces HTTP et admissions sont persistées au fil de l’eau, y compris en
échec. Le dernier polling conserve uniquement des champs allowlistés. Les
preuves sont liées aux cinq incarnations runtime effectives. `status` expose
séparément disponibilité technique, preuve métier, fraîcheur, dernier essai,
dernier succès et génération qualifiée. `status --run` rapproche un résultat
tardif par GET, sans réadmission ni réécriture du verdict initial.

La mise à jour du seul tooling d’une instance existante vérifie sa compatibilité
productive, ses JAR et son Compose avant adoption. La graphie des montures est
conservée pour éviter les recréations `/Dev/` ↔ `/dev/`.
Le [contrat détaillé](L7_Local_Playground_Recovery_and_Evidence.md) définit les
critères et limites de ces observations.

## Vérification ciblée

Commande :

```sh
python3 -B -m unittest discover -s scripts/local -p 'test_*.py'
```

80 tests PASS, zéro échec, zéro erreur. Les tests existants restent actifs ;
l’ancien test du simple délai de récupération est remplacé par un contrôle
plus fort des 36 partitions et du SQL strictement READ ONLY. Les cas nouveaux
couvrent création, conservation, arrêt/redémarrage, recréation, couverture
partielle, délai borné, observation impossible, changement de génération,
preuve fraîche étrangère/ancienne, échec de start, traces de timeout, erreurs
réseau à l’ouverture et à la lecture, 404/503, résultat tardif sans POST et refus
de dérive productive. Preuve :
[`python-tests.txt`](../testing/evidence/L7-LB-RECOVERY/python-tests.txt).

Une première exécution sandboxée n’a pas pu réserver les ports loopback des
tests existants ; la même suite autorisée avec accès loopback a été exécutée.
Les erreurs de développement intermédiaires ne constituent pas un résultat
PASS et ne sont pas substituées aux preuves finales.

## Qualification Docker réelle

Verdict : **L7_LB_START_RECOVERY_FIXED_AND_QUALIFIED**.

Commande réelle :

```sh
python3 -B scripts/local/test_recovery_installation.py \
  --fixture recovery-evidence-fix \
  --output docs/testing/evidence/L7-LB-RECOVERY/docker-qualified
```

Le [reçu final](../testing/evidence/L7-LB-RECOVERY/docker-qualified/qualification.json)
est PASS. SHA observé pendant les tests : `b7b12c3d` avec correctif et
modifications Bruno préexistantes présents dans le checkout dirty. Empreinte
des entrées effectivement qualifiées :
`266f0f638960da638f5370be64f134bfbde85e96ee4beea6f8645e1df9e571d4`.

| Phase réelle | Résultat | Mesure |
|---|---|---|
| Demo existante, démarrage nominal | READY, aucun conteneur recréé | 11,860 s ; une observation de récupération, 1,022 s |
| Alias `/dev/` | READY, identifiants des neuf conteneurs conservés | 10,186 s |
| Premier start de fixture | READY | 54,910 s bootstrap inclus ; récupération 1,115 s, une observation |
| Recréation des trois workers pendant start | READY, nouvelles générations détectées | 232,348 s de récupération ; 190 observations |
| Canary après récupération | PASS | 0,820 s après observation de l’admission UpdatePotDetails ; budget 30 s inchangé |
| Mutation à la version résultante | Exactement une | SQL WRITE : un événement |
| Status après recréation | READY, génération qualifiée | Anciennes générations non réutilisées |
| Bruno demo / fixture | PASS / PASS | 236 tests nommés chacun ; 730 / 731 assertions |
| Restauration demo | READY | Couverture complète à la première observation |
| Résultat tardif de l’incident historique | HTTP 200, COMPLETED, version 4 | GET uniquement ; verdict initial NOT_READY conservé |

Chronologie physique du 15 novembre 2026, en UTC (Paris = UTC+1) :

- 10:11:04.442 : photographie SQL avant recréation ; 8 baux WRITE encore
  valides 239,437–239,449 s et 28 READ encore valides 237,342–238,475 s.
- 10:11:20.692 : début du contrôle de récupération après recréation.
- Pendant l’attente : workers RUNNING, zéro bail local utilisable ; aucune
  admission métier de canari.
- 10:15:13.040 : les 36 couples sont couverts ; récupération validée.
- 10:15:13.066 : observation du 202 du CreatePot idempotent déjà existant.
- 10:15:13.096 : observation du 202 de la nouvelle mutation UpdatePotDetails,
  requestId `fa578dd2-973c-4aa4-b5b1-a94c6be55b09`.
- Environ 0,820 s plus tard : canari complet réussi, puis contrôles finaux et
  status de la même génération réussis.

La [synthèse de recréation](../testing/evidence/L7-LB-RECOVERY/docker-qualified/recreation-summary.json)
et les fichiers `fixture-recreated/{worker-recovery,command-observations,public-requests}.json`
conservent les générations, les observations et les horodatages sans dépendre
de Prometheus historique. Les contrôles Keycloak/PKCE, API WRITE/READ,
Prometheus, Grafana et dashboards sont PASS dans les reçus conservés.

La première campagne `docker/` s’est interrompue sur `PRE-INSTANCE-LOCKED`
avant l’arrêt de demo et avant la création de fixture. Son collecteur gérait
mal l’absence de chemin de preuves de ce refus ; il a été corrigé. Les preuves
restent conservées. L’inspection ultérieure du lock et des processus n’a trouvé
aucun propriétaire. Aucun reset, suppression de volume ou relance aveugle du
résultat de cette campagne n’est revendiqué.

La campagne distincte `docker-qualified/` emploie une fixture neuve
`recovery-evidence-fix`. L’injection de test force uniquement les trois workers
pendant l’appel réel à `start`, après sa photographie initiale. Les API,
PostgreSQL, Keycloak et les autres services de cette fixture sont conservés.
Ce hook n’existe pas dans le parcours produit. Les baux sont observés en SQL
avant remplacement, sans mutation de leurs échéances. La qualification exige
une attente réellement observée, puis les 36 partitions, puis l’admission,
un canari inférieur à 30 s et une seule mutation à la nouvelle version.

## Préservation et limites

Demo n’est jamais reset. Ses secrets, JAR et seed sont comparés par hashes ;
les Pot préexistants et les entités/configurations Keycloak sont contrôlés.
Un audit SQL READ ONLY des événements confirme zéro mutation sur les Pot
préexistants hors canari durant la qualification ; preuve
`docker-qualified/existing-domain-preservation.json`. Ce contrôle supplémentaire
READ ONLY a été exécuté après la campagne principale puis intégré au collecteur
pour sa reproduction. Aucun code d’orchestration ou de canari n’a changé après
la qualification Docker finale.
Les campagnes Bruno ajoutent leurs objets de qualification déclarés. Les
conteneurs externes restent inchangés et les volumes préexistants conservés.
La fixture est arrêtée et conservée pour diagnostic ; demo est restaurée active.

Cette preuve locale ne clôture ni L7 global, ni I4/I5/I6, ni les réserves
historiques. Les règles de fraîcheur de 300 s restent intentionnelles ; un
status exécuté après cette période exige un nouveau start pour renouveler la
preuve métier. Le rapprochement tardif ne remplace pas les vérifications du
canari qui n’ont pas été exécutées.

## Publication et état final

Le commit de livraison porte le correctif, ses tests, sa documentation et les
preuves allowlistées. Les dix modifications Bruno préexistantes restent
strictement inchangées et non stagées. Aucun changement `app/`, aucune migration,
aucun commit Bruno, aucun franchissement de slice inattendu. Le rapport ne
promet pas l’inclusion des modifications Bruno non publiées dans un clone neuf.
Le SHA publié est communiqué dans la livraison ; les preuves sont attribuées
au SHA de départ dirty et à l’empreinte exacte ci-dessus, sans réécrire leur
provenance après commit.
