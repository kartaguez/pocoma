# Pocoma V3 — playground local

Mandat Jalon 1 : I1a/I2/I3. La [qualification](L7_Local_Playground_Qualification_Report.md)
porte le verdict de livraison ; READY est un état d'exécution daté, distinct de
l'acceptation humaine et de V3_REFERENCE_BASELINE_ACCEPTED.

## Démarrer depuis Git

Prérequis hôte : macOS ARM64, Docker Desktop démarré (VM ≥6 Gio, testé à 8 Gio),
Java/JDK 21, Python ≥3.11, Node 24/npm, Git, réseau pour Maven Central et les
registres d'images. Les outils hôte se préparent une fois ; la façade vérifie les
versions et installe les dépendances Bruno verrouillées. Prévoir ≥4 Gio libres
au contrôle initial et davantage pour les téléchargements/caches.

```sh
git clone https://github.com/kartaguez/pocoma.git
cd pocoma
git switch feat/l7-lb-local-playground
./pocoma-local start
```

La commande construit les quatre JAR Java 21 nécessaires aux **cinq JVM réelles**,
récupère les images natives, crée PG/Keycloak, initialise WRITE/READ puis le jeu
public et le canari. Pas de JAR Pocoma préinstallé nécessaire. Le Maven Wrapper
est verrouillé en 3.9.14 ; les quatre modules sont assemblés avec `-am package
-DskipTests`. Ce build n'est pas une preuve de test Maven.

La sortie JSON fournit **write**, **read**, **issuer**, le projet et le dossier
de diagnostic. Les ports loopback sont attribués automatiquement puis conservés.
Aucun port PG n'est publié. Chaque instance utilise son réseau, son cluster PG
(WRITE/READ et stockage Keycloak), son volume, son realm et ses secrets. Aucun
nom/volume du Compose historique n'est réutilisé. Images versionnées : PG
17.6-alpine, Keycloak 26.7.0, JRE Temurin 21.0.8_9, Prometheus 3.5.0 ; leurs
digests natifs effectifs sont dans le manifeste privé et la preuve expurgée.

Les cinq JVM ont chacune un heap max de 384 Mio et une limite de 640 Mio ; PG
1024 Mio, Keycloak 896 Mio, Prometheus 256 Mio, soit 5376 Mio de limites de
conteneurs. Les instances de playground s'exécutent successivement sur 16 Go :
un autre playground actif est refusé, jamais arrêté implicitement. Les autres
projets Docker ne sont pas modifiés.

## Cycle de vie et diagnostics

```sh
./pocoma-local status
./pocoma-local bruno
./pocoma-local stop
./pocoma-local start
./pocoma-local reset --instance demo --confirm RESET:demo --dry-run
./pocoma-local reset --instance demo --confirm RESET:demo
```

`--instance alice` fonctionne sur toutes les commandes. Reset exige **toujours**
l'instance explicite et `RESET:<instance>` ; il affiche son projet/volume en
mode dry-run. Reset supprime uniquement le volume contrôlé par UUID/labels de
cette instance, puis rebâtit ses données et sa nouvelle identité d'instance.
Les diagnostics précédents restent conservés. Stop attend l'arrêt gracieux des
JVM (45 s par service) puis des dépendances ; volumes et baux sont conservés.
Le code d'arrêt Docker est conservé dans les reçus/logs privés ; aucun slot
n'est purgé et aucun bail n'est raccourci. Une reprise après arrêt forcé peut
attendre les baux existants (lease 240 s).

Le verrou d'instance refuse les opérations concurrentes ; un second verrou
sérialise start/reset entre instances, un verrou de build protège Maven. Les
verrous POSIX se libèrent à la fin du processus, y compris après crash. Ne pas
exécuter Maven manuellement en parallèle avec un start. Aucune commande globale
`prune` ni suppression implicite à l'échec. Symlink, absence de manifeste,
propriété incohérente et ressource étrangère sont refusés avant mutation.

État privé : `.pocoma-local/instances/<id>/manifest.json`, `secrets.json`,
configurations, `demo.json`, `canary.json`, `runs/<run-id>/`. Racine et dossiers
privés 0700, secrets 0600 ; les seules montures lues par les UID des conteneurs
sont 0644 **sous ces dossiers privés**. Ne pas publier cet arbre. Les mots de
passe synthétiques sont consultables localement dans `secrets.json` pour le
login GUI ; ils ne doivent pas être copiés dans Git, tickets ou commandes.

Après échec, garder le JSON et le dossier `evidence`. Les logs conteneurs
expurgés sont privés ; pas de copie automatique de bodies HTTP ni de bearer.
Le bootstrap est transactionnel **par base**, marque chaque base seulement
après SQL et grants complets ; une interruption entre WRITE et READ reprend
la base manquante. Aucun baseline TRUSTED ni migration historique V2 n'est
supposé. Un changement d'entrées productives/SQL/outillage fait refuser le
start existant : utiliser une nouvelle instance ou reset explicite après
avoir conservé ses données. Pas de mise à niveau automatique.

## Readiness et codes

| Code | État / sens |
|---|---|
| 0 | READY : métier/authentification disponibles, canari actuel et outils d'observabilité disponibles ; ou action stop/Bruno/login réussie |
| 1 | NOT_READY, STOPPED au status, canari périmé/non prouvé, échec métier ou transport |
| 2 | Usage/prérequis/cible/propriété/drift refusés |
| 3 | Conflit de verrou |
| 4 | DEGRADED : métier et canari disponibles, observabilité indisponible ; ce n'est pas une indisponibilité métier |
| 130 | Interruption contrôlée ; diagnostics et état conservés |

Le JSON sépare `business`, `canary`, `qualificationTools`. `fullyQualifiable`
reste false pour ce jalon : les campagnes de qualification globale/I4–I6 ne
sont pas livrées. Les outils d'observabilité demandent cinq scrapes UP, des
métriques JVM et Pocoma présentes. Une réponse Actuator seule ne suffit pas :
PG, empreintes de catalogue, rôles actifs, maintenance, issuer et session PKCE
sont contrôlés ; le canari exige une nouvelle version et son contenu/balance.

`status` ne soumet aucune commande métier : il relit les dépendances, ouvre
une session PKCE, contrôle le canari et sa fraîcheur **300 s max**. Après cette
borne, il renvoie NOT_READY/canari STALE_OR_UNPROVEN ; relancer start pour une
nouvelle preuve. Start réutilise **un seul Pot canari**, avance son libellé et
sa version avec une clé unique par invocation. Une preuve ancienne ne ferme
pas la nouvelle invocation.

## Jeu de démonstration v1

Seed entièrement public, checkpointé et rejouable après interruption :

- `bruno` propriétaire et `guest` invité, identités Keycloak réelles distinctes ;
- Pot « Pocoma playground », deux parts de poids 1, dépense 10/3 ; soldes
  attendus +5/3 au payeur et −5/3 à l'invité, version 4 après acceptation ;
- Pot « Deleted demo history » supprimé à V2, histoire V1 consultable ;
- Pot canari séparé, balances vides exactes ; nouvelles versions à chaque start ;
- `third` disponible pour Bruno : sa Registration initiale est réelle ; chaque
  exécution Bruno crée son Pot de parcours et le supprime en conservant l'histoire.

Après seed complet, start ne réapplique pas la demo et n'écrase aucune mutation
utilisateur ; `demo.json` décrit l'état **initial attendu**, pas une certification
de l'état courant modifié par le développeur. Reset seul restaure ce jeu.
Aucune suite B1 injectant READ n'est créditée comme preuve de ce parcours.

Guides : [Bruno GUI/CLI](L7_Local_Playground_Bruno.md),
[contrat consommateur BFF](L7_Local_Playground_Public_API.md).
`verify`/`bench`, performance exhaustive, réconciliation I1b et certification
restent des travaux I4–I6 ; la façade ne les annonce pas comme disponibles.
