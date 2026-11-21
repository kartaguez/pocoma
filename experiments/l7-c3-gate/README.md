# Harnais isolé L7-C3

Prérequis locaux. Java 21, le dépôt complet, Docker/Testcontainers, Bruno au chemin du Monolith et les classes de test du module `runtime-worker-materialize`. Utiliser le classpath enregistré par un run Maven sur les mêmes sources. Le harnais crée son conteneur PostgreSQL 17.11 et ses propres autorités. Le scénario d’ordre termine un backend de cette fixture pour exercer la reprise WAL.

Depuis la racine du dépôt, après la commande composée demandée

```sh
(cd app && ./mvnw --fail-at-end -pl runtime-worker-materialize,architecture-tests -am test)
bash experiments/l7-c3-gate/run.sh /tmp/c3-order order
bash experiments/l7-c3-gate/run.sh /tmp/c3-cost cost 100
python3 -B experiments/l7-c3-gate/summarize-cost.py /tmp/c3-cost
```

L’essai d’ordre combine une lecture SQL indépendante, des barrières avant COMMIT, un blocage PostgreSQL observé, la reprise après révocation et la reprise WAL. Le test de forme SQL essaie le suffixe CTE sur la vraie V5, avec une collision UUID comme contrôle négatif.

La campagne A/B utilise douze finalisations d’échauffement par famille avant chaque fenêtre. Le corpus WRITE est préparé hors fenêtre et tous les slots de test sont vérifiés avant mesure. Avec quatre workers, chaque thread traite une partition de routage pour éviter qu’une acquisition concurrente sur la même partition ne change le corpus mesuré. L’Engine, les participants, les stores C2 et leurs transactions sont réels. Les deux autorités sont des bases distinctes avec rôles distincts dans un même conteneur.

| Profil | Activation A/B |
| --- | --- |
| CommandResult | V4 et writer existant / vraie V5 dans la composition pilote |
| IdentityRequestResult, PotVersion, PotBalance | Participants existants / mêmes participants avec preuve et suffixe SQL dans une fixture gardée |
| Mixte, quatre workers | Les trois matérialiseurs de `CreatePot` / publication de CommandResult seule, pour observer les deux autres sous charge partagée |

Le SQL expérimental pour les trois familles reproduit la validation d’absence et l’allocation suivie de l’insertion historique d’un événement par effet neuf. Il contrôle l’absence de positions orphelines, le lien dans les deux sens entre l’effet et l’événement sur les profils purs, ainsi que la densité finale. Le scope expérimental couvre les clés neuves du corpus. Il laisse les oracles de recovery de production en V4 et ne prétend pas implémenter les courants ou un chemin de publication générique.

Le mode `wal 100` rejoue les profils à un worker et décompose leurs intervalles LSN avec
`pg_walinspect`. Il sert à séparer le WAL des instructions suivies du reste du WAL cluster.
Aucun regroupement de ses latences avec la campagne de coûts principale.

Les fenêtres brutes contiennent le temps d’exécution et l’ensemble acquisition + exécution, les statements JDBC préparés, le temps d’exécution SQL agrégé, les COMMIT/ROLLBACK observés, le WAL du cluster, le CPU du conteneur et celui des threads de travail. L’observateur échantillonne les événements de wait PostgreSQL toutes les 5 ms environ. Le journal SQL permet d’obtenir une durée exacte pour les attentes qui dépassent le seuil de diagnostic de 5 ms. Le banc mesure la fixture, avec connexions directes et sans HTTP. Les valeurs C2 et SPEC-09 restent des références externes à ces fenêtres.
