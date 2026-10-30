# Reproduction du banc indépendant

Audit du SHA `2b3567b1f77051c399772ee7ac787f7dd61ba407`. Aucun fichier productif ni test existant modifié.

Les chemins des scripts correspondent au checkout isolé `/private/tmp/pocoma-l3-i3-audit-2b3567b` et au dossier de preuve `/private/tmp/pocoma-l3-i3-audit-evidence`. Pour une nouvelle exécution ailleurs, adapter ces deux chemins et le port local 55439. Les données sont entièrement synthétiques. Ne jamais viser une base préexistante.

1. Créer un clone isolé et détacher le SHA demandé.
2. Exécuter depuis `app/` :

```sh
./mvnw -o -pl runtime-command-consumption-worker -am -Dtest=IdentityPrivilegesPostgresTest,V3IdentityRequestCompositionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
```

C'est une contribution ciblée, pas une gate canonique. Docker et accès réseau local nécessaires. `classpath.txt` contient le `java.class.path` du XML Surefire de `V3IdentityRequestCompositionPostgresTest` fraîchement généré. `capture_classpath.py` le reconstruit et compile le banc externe.

3. Lancer exclusivement un serveur neuf pour l'audit :

```sh
docker run -d --name pocoma-l3-i3-independent-audit -e POSTGRES_HOST_AUTH_METHOD=trust -p 127.0.0.1:55439:5432 postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73 -c log_min_error_statement=error
python3 -B /private/tmp/pocoma-l3-i3-audit-evidence/prepare.py
python3 -B /private/tmp/pocoma-l3-i3-audit-evidence/capture_classpath.py
```

4. Exécuter successivement :

```sh
python3 -B /private/tmp/pocoma-l3-i3-audit-evidence/run_java.py normal
python3 -B /private/tmp/pocoma-l3-i3-audit-evidence/run_java.py retained
python3 -B /private/tmp/pocoma-l3-i3-audit-evidence/run_java.py concurrent
python3 -B /private/tmp/pocoma-l3-i3-audit-evidence/run_java.py reconnect
python3 -B /private/tmp/pocoma-l3-i3-audit-evidence/run_java.py seed
python3 -B /private/tmp/pocoma-l3-i3-audit-evidence/run_java.py unknown
python3 -B /private/tmp/pocoma-l3-i3-audit-evidence/run_java.py memory
python3 -B /private/tmp/pocoma-l3-i3-audit-evidence/run_java.py collision
python3 -B /private/tmp/pocoma-l3-i3-audit-evidence/security_probes.py
```

`security_probes.py` applique des grants adversariaux uniquement sur le serveur jetable, les retire après chaque cas, puis démarre le vrai Worker avec une vue exposée. Le mode `boot-view` supplémentaire vérifie le manager JPA réel. Les sondes TRUNCATE initiales font rollback.

Pour vérifier la fuite dans le journal serveur, lire `docker logs` uniquement dans un processus local, rechercher le marqueur de test défini dans la méthode `seed`, puis émettre seulement le booléen de présence ou une ligne dont la valeur est remplacée par `[REDACTED_TEST_TOKEN]`. Ne pas exporter les journaux bruts. Le reçu expurgé de l'exécution réalisée est fourni.

5. **Dernière étape uniquement, jeu synthétique devenu sacrifiable :**

```sh
python3 -B /private/tmp/pocoma-l3-i3-audit-evidence/commit_probes.py
```

Cette étape commet volontairement deux TRUNCATE, vérifie par une connexion admin indépendante les événements supprimés et les positions orphelines, puis retire les grants. Le jeu n'est plus cohérent ; ne pas le réutiliser pour d'autres preuves métier.

6. Retirer uniquement le conteneur d'audit :

```sh
docker rm -f pocoma-l3-i3-independent-audit
```

Les `.log` antérieurs sont conservés. Les premiers essais sans accès au socket Docker/TCP local, les erreurs de compilation du banc et l'essai de réapplication du profil lifecycle étroit après Discovery ne sont pas des échecs applicatifs. Les rapports distinguent ces essais des exécutions corrigées. Aucun timeout L2 ni oracle existant n'a été changé.
