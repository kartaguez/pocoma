# Pocoma V3 — Correctif ciblé du helper L3/I2 waiterPid

**3 novembre 2026 — PASS local ciblé, 11/0/0/0.**
Branche `feat/v3-architecture`, SHA initial `2a81d49ebeb595cc2c46f14b3d850cbf92863f93`.
Le test porte sur ce HEAD plus le seul diff applicatif ci-dessous ; les empreintes exactes
et l’état dirty sont dans la provenance. Aucun code de production, assertion, protection,
seuil, sleep ou retry artificiel n’est modifié.

## Frontière déclarée avant implémentation

- Contribution locale filtrée dans COMMAND : helper de `infra-persistence-primary-jpa`.
- Slice secondaire : NONE. Impact production interdit : tout changement.
- Franchissement inattendu : NONE. Gate Architecture : NOT REQUIRED / NOT RUN.
- Full reactor : NOT REQUIRED / NOT RUN ; correction de helper sans frontière ni dépendance changée.
- Base : CURRENT V3, migrations existantes de la fixture ; aucune campagne historique V1–V23.
- Budget maximal : 300 s, sans élargissement ; interruption et conservation des diagnostics prévues.

## Diff fonctionnel complet

Fichier :
`app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityPotAuthorityPostgresTest.java`.

```diff
 while (System.nanoTime() < deadline) {
+    var snapshot = List.copyOf(lockCalls);
     var call =
-            lockCalls.subList(mark, lockCalls.size()).stream()
+            snapshot.subList(mark, snapshot.size()).stream()
                     .filter(c -> c.pid() != holder && c.key().equals(key))
                     .reduce((a, b) -> b);
```

`lockCalls` est une CopyOnWriteArrayList append-only de LockCall non nuls. Les trois
appelants calculent `mark = lockCalls.size()` avant la soumission du waiter. La copie est
créée ensuite, donc `0 ≤ mark ≤ snapshot.size()` ; aucun clamp n’est nécessaire et un index
invalide ne serait pas masqué. `mark == snapshot.size()` donne un suffixe vide ; `mark == 0`
garde toute la copie. Le préfixe antérieur à mark reste exclu. Les ajouts pendant la copie
ou le parcours n’invalident pas ce snapshot ; un ajout absent de cette copie est éligible
au tour suivant de la boucle existante. Le dernier PID correspondant à la clé et différent
du holder reste choisi. Le délai 10 s, Thread.yield, erreur finale, observations SQL,
barrières, blocage physique et assertions des appelants restent strictement identiques.

La classe existante suffit à la vérification ciblée : `l3_registration_vs_command`,
`l3_command_vs_unlink_and_link` et `l3_identity_first_commit_and_rollback` traversent ce
helper avec des ajouts concurrents du waiter et conservent leurs témoins PostgreSQL.
Ce PASS ne prétend pas être une reproduction déterministe de chaque ordonnancement de la CME.

## Commandes et résultat effectif

Depuis la racine :

```sh
python3 -B docs/testing/evidence/L3-I2-waiterpid/run-targeted.py
```

L’opérateur réutilise sans modification `scripts/testing/verify.py`, avec une recette positive
en mémoire limitée à cette classe. Depuis `app/`, argv Maven du PASS (aucun filtre caché) :

```sh
./mvnw --batch-mode --no-transfer-progress -pl infra-persistence-primary-jpa -am -Dtest=com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityPotAuthorityPostgresTest -Dsurefire.failIfNoSpecifiedTests=false -Dpocoma.verification.id=948ae2bc757f44d0bc2ccb1cd14ea20e -Dpocoma.verification.reports=/Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L3-I2-waiterpid/targeted/reports test
```

La découverte préalable exécute le même argv avec `help:effective-pom` et `-Doutput=…`
à la place de `test`. Les modules `-am` compilent leurs dépendances, sans autre suite exécutée.
Le plan conserve le modèle et les 11 identités attendues ; le collecteur confirme exactement
11 invocations fraîches, zéro inattendue/dupliquée/manquante, 0 failure/error/skip, `issues=[]`.
ID du PASS : `948ae2bc757f44d0bc2ccb1cd14ea20e`.

Durées mesurées : Maven **12,817 s**, découverte **1,597 s**, opérateur complet **15,291 s**.
Le premier lancement sandbox a duré **5,184 s** : socket Docker refusé, aucune méthode exécutée,
FAIL du collecteur (compteur Surefire 1 testcase synthétique, 0 failure, 1 error, 0 skip).
Le même test a été relancé avec l’accès Docker autorisé, sans changement applicatif ni assertion.
La fenêtre totale entre premier lancement et PASS, diagnostic compris, est **inférieure à 60 s**.
Les deux résultats et toutes les preuves sont conservés, sans effacer le premier FAIL.
PostgreSQL observé : **17.11**, JDK **Temurin 21**, Maven **3.9.14**.

[Preuves et opérateur](../testing/evidence/L3-I2-waiterpid/README.md),
[reçu PASS](../testing/evidence/L3-I2-waiterpid/targeted/result.json),
[provenance](../testing/evidence/L3-I2-waiterpid/targeted/provenance.json),
[archive brute](../testing/evidence/L3-I2-waiterpid/targeted/raw.tar.gz).
L’inventaire attendu contient 11 méthodes de cette seule classe.

## Portée et livraison

Aucune commande de gate globale COMMAND, PROJECTION, Architecture ou full reactor n’a été
lancée. Aucun franchissement de slice. Le PASS valide uniquement ce helper ; il ne requalifie
ni L3 ni L4 globalement. La clôture L4/I2 est indépendante et s’appuie sur ses gates déjà acquises
au SHA `6496e6d0144ecfda9e58ec6d12e259445e7344ad` ; leurs archives demeurent inchangées.
Le correctif devra être inclus dans la campagne globale distincte à la clôture L4,
après cadrage explicite du périmètre, des durées maximales et des critères d’arrêt.

Deux commits prévus : helper et preuves ciblées ; clôture documentaire L4/I2 et report global.
Les SHA finaux et la comparaison directe local/distant figurent dans le message de livraison.
