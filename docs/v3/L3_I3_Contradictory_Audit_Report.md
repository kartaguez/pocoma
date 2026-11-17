# Pocoma V3 L3/I3 — Audit contradictoire indépendant

**Verdict : FAIL — CORRECTIONS REQUIRED.**

Pièces : [protocole et scripts](../testing/evidence/L3-I3-CT/README.md), [inventaire SHA-256 des sources](../testing/evidence/L3-I3-CT/sources-manifest.json), [manifeste des preuves](../testing/evidence/L3-I3-CT/manifest.json). Le conteneur jetable a été supprimé après collecte ; le clone isolé et les fichiers de preuve restent disponibles.

Deux défauts significatifs sont reproduits sur l'axe des autorisations : accès READ indirect à des données sensibles avec préflight accepté, et privilèges TRUNCATE excessifs des rôles de réactivation/injection acceptés par les contrôles. Deux constats complémentaires sont documentés : protection Java périmée dans une composition JDBC particulière, et token synthétique présent dans un diagnostic serveur PostgreSQL.

**Les grants livrés sur une installation neuve ne donnent pas directement les tokens au rôle READ.** Les défauts majeurs concernent la détection de configurations partiellement erronées, précisément incluse dans le mandat. Les expériences adversariales supposent un grant ou un objet ajouté par un administrateur ; elles ne démontrent pas qu'un rôle READ puisse s'octroyer seul ces droits.

La durée de vie des protections est satisfaisante dans les chemins Hikari ordinaires éprouvés, y compris le manager JPA du vrai Worker. Le contre-exemple de holder persistant concerne le chemin JDBC supporté par l'owner, sans contournement démontré du participant ou du Worker livré.

## 1. Référence et périmètre réellement audités

- Dépôt demandé : `kartaguez/pocoma`, référence de branche `feat/v3-architecture`.
- **SHA effectivement lu, compilé et éprouvé : `2b3567b1f77051c399772ee7ac787f7dd61ba407`.**
- Clone local isolé, HEAD détachée : `/private/tmp/pocoma-l3-i3-audit-2b3567b`.
- Rapport, banc et preuves externes : `/private/tmp/pocoma-l3-i3-audit-evidence`.
- Checkout d'origine : `/Users/julien.guezennec/Dev/projects/pocoma`, HEAD `90a3e9bc…`, travaux préexistants conservés. Son état Git final correspond à l'inventaire initial observé.
- Aucun changement productif, test existant, oracle, migration publiée ou timeout L2. Aucun commit, push, reset, stash, clean ni checkout destructeur. Aucun I4 commencé.
- `git status --porcelain` du clone isolé vide et `git diff --exit-code <SHA>` sans différence après expériences. Seuls les produits de compilation ignorés sont créés dans le clone.
- La branche distante n'a pas été revalidée : l'objet Git exact fourni est la référence de cet audit, pas la tête éventuellement mouvante de la branche.

### Périmètre de vérification déclaré

Politique normative du SHA lue avant les vérifications sous `app/` : `docs/testing/Reactor_Verification_Policy.md`.

| Champ | Déclaration / réalisation |
|---|---|
| Impact productif | Aucun, audit en lecture seule des sources |
| Capacité examinée | COMMAND / Identity, protections et privilèges seulement |
| Slices secondaires | NONE ; monolithe consulté statiquement, non réexécuté |
| Preuves nouvelles | Banc externe Java/SQL, PostgreSQL réel ; six tests existants ciblés en corroboration |
| Commande canonique COMMAND | `./mvnw -pl runtime-command-consumption-worker -am test`, **non exécutée** dans ce mandat ciblé |
| Gate globale Architecture | NOT REQUIRED pour cet audit sans changement ; non exécutée |
| Full reactor | NOT REQUIRED / NOT RUN ; aucune clôture Wave, main ou certification globale |
| Franchissement inattendu | Aucun ; les grants et données adversariaux restent dans le banc jetable |
| DB | CURRENT V3 WRITE V1–V6, READ technique V1 ; aucune migration historique V2, aucune baseline déclarée TRUSTED |

Les échecs canoniques historiques `i5_r12` et l'état Architecture SKIPPED ne sont ni rejoués ni requalifiés.

## 2. Inventaire des sources consultées

Les documents servent à formuler les invariants ; aucune affirmation documentaire n'est comptée comme preuve dynamique. Le manifeste joint conserve les chemins et SHA-256 des sources inventoriées.

### Documents demandés et nom réel

Tous sont présents sous `docs/v3/`, sauf le nom SPEC-06 suggéré dans la demande :

1. `L3_Identity_Inventory_and_Implementation_Plan.md` : en particulier §§3.1–3.3, 4.2/4.2.1, 5.2 et obligations I2/I3.
2. `L3_I2_Execution_Report.md`.
3. `L3_I3_Execution_Report.md`.
4. `L3_I3_Identity_Development_Runbook.md`.
5. `SPEC-02_Identity_Domain.md` : tokens, préconditions, verrous, atomicité.
6. `SPEC-03_Events_Requests_Consumables.md` : demande immuable, événements minimaux, unité physique.
7. `SPEC-05_Consumption_Discovery.md` : §4 protection finale, §5 UNKNOWN/recovery et R9.
8. **`SPEC-06_Supra_Contracts.md`**, nom réel ; aucun `SPEC-06_Transactions_and_Concurrency.md` trouvé. Admission/provenance, confidentialité et frontières de confiance examinées.
9. `SPEC-10_Test_Certification.md` : exigences et protocoles pertinents ID-01/02, EVT-02, CON-04/06, preuve négative et statut des observations.

### Sources productives prioritaires

Sous `app/` :

- `infra-persistence-primary-jpa/.../v3/identity/JdbcIdentityAuthority.java`.
- `infra-tx-spring/.../v3/SpringAttemptTransactions.java`.
- `engine-consume-registration/.../v3/ExecuteIdentityRequest.java`.
- `infra-persistence-primary-jpa/.../v3/identity/JdbcIdentityRequestStore.java`, `JdbcIdentityRequestObservation.java`, `IdentityRequestJson.java`, `SecureBindingTokenGenerator.java`.
- `composition-runtime-spring/.../v3/V3IdentityRequestComposition.java`, `V3BootstrapGuard.java`, `V3ConsumptionWorkerSettings.java`, `V3ConsumptionWorkerConfiguration.java`.
- `contracts-registration/.../v3/IdentityRequest.java`, valeurs Identity et `port-transaction/.../v3/AttemptTransactions.java`.
- **Migration Identity exacte :** `infra-persistence-primary-jpa/src/main/resources/db/v3-identity/migration/V6__identity_authority_and_requests.sql`. Les autres V6 historiques ne sont pas la migration auditée.
- Configurations DEV des deux racines, scripts `bootstrap-l2-routing.sql`, `bootstrap-l2-worker-dev.sql`, `grant-l2-lifecycle.sql`, `grant-l2-discovery.sql`, `grant-l2-worker-dev.sql` et chemin administratif `scripts/v3/worker-dev.py`.

Les bootstraps CURRENT V3 prérequis V1–V5 et READ V1 ont été consommés pour construire le banc, sans revue générale de leurs autres capacités.

### Tests existants examinés

Méthodes et supports pertinents de `IdentityAuthorityPostgresTest`, `IdentityConcurrencyPostgresTest`, `IdentitySchemaPostgresTest`, `IdentityPrivilegesPostgresTest`, `IdentityRequestExecutionPostgresTest` — notamment `l3_unknown_commit` — et `IdentityPublicationPostgresTest` ; parcours de composition dans `V3IdentityRequestCompositionPostgresTest` et `V3IdentityRequestMonolithPostgresTest`. Les tests Discovery et les traces historiques ne constituent pas ici une nouvelle certification de Discovery.

**Fraîchement exécutés :** les trois méthodes de `IdentityPrivilegesPostgresTest` et les trois de `V3IdentityRequestCompositionPostgresTest`, uniquement. Les autres résultats publiés sont des sources examinées, pas des exécutions attribuées à cet audit.

## 3. Environnement, commandes et protocole

### Environnement constaté

- macOS 27.0 / aarch64, Temurin Java 21, Maven 3.9.14.
- Spring JDBC/TX/ORM **7.0.6**, HikariCP **7.0.2**, pgjdbc **42.7.10**, Spring Boot **4.0.5**.
- PostgreSQL **17.11** aarch64 Linux musl.
- Image locale lancée sous le tag `postgres:17-alpine`, dont le digest inspecté est **`postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73`**, identique au digest des tests livrés.
- Conteneur réservé `pocoma-l3-i3-independent-audit`, port `127.0.0.1:55439`, deux bases neuves `audit_write` / `audit_read`, schéma `audit`, propriétaire `postgres`.
- Quatre logins sans superuser/CREATEDB/CREATEROLE/BYPASSRLS : `audit_w` (Business et Control), `audit_r`, `audit_a` (réactivation), `audit_i` (injecteur). Connexions directes sous ces rôles, pas seulement un `SET ROLE` administrateur.
- Authentification trust réservée à ce serveur jetable local ; ce montage ne qualifie pas une politique d'authentification de production.
- `currentSchema=audit` sur les connexions Java. CREATE/TEMP refusés aux runtimes par les scripts livrés ; PUBLIC n'a aucun privilège Identity dans l'état de référence du banc.

Le bootstrap indépendant lit les SQL du SHA, substitue les paramètres V5 et les exécute par psql, puis applique les cinq scripts administratifs livrés. **Il ne crée pas de history Flyway.** Les six tests existants et le vrai démarrage Spring corroborent le raccord ; l'équivalence des mécanismes de migration n'est pas un objet de preuve de ce banc.

### Commandes exécutées

Depuis le checkout d'origine : `pwd`, `git status --short`, `git rev-parse HEAD`, `git cat-file -t <SHA>` ; lecture de la politique. Puis :

```sh
git clone --shared --no-checkout /Users/julien.guezennec/Dev/projects/pocoma /private/tmp/pocoma-l3-i3-audit-2b3567b
git -C /private/tmp/pocoma-l3-i3-audit-2b3567b checkout --detach 2b3567b1f77051c399772ee7ac787f7dd61ba407
```

Depuis `app/`, commande exacte de corroboration ciblée :

```sh
./mvnw -o -pl runtime-command-consumption-worker -am -Dtest=IdentityPrivilegesPostgresTest,V3IdentityRequestCompositionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Première tentative : FAIL environnement, socket Docker inaccessible, une erreur de démarrage Testcontainers, aucun succès applicatif déduit. Reprise avec accès Docker autorisé : **BUILD SUCCESS, 56,801 s ; 6 tests, 0 failure, 0 error, 0 skip**. La correction d'accès est la raison de cette reprise, pas une recherche de PASS probabiliste.

Serveur indépendant :

```sh
docker run -d --name pocoma-l3-i3-independent-audit -e POSTGRES_HOST_AUTH_METHOD=trust -p 127.0.0.1:55439:5432 postgres:17-alpine -c log_min_error_statement=error
python3 -B /private/tmp/pocoma-l3-i3-audit-evidence/prepare.py
```

Compilation externe par `javac -cp <classpath Surefire enregistré> -d <dossier de preuve> IndependentAudit.java`. Les invocations Java sont orchestrées sans interpolation shell dans `run_java.py` et `security_probes.py` : modes `normal`, `retained`, `concurrent`, `reconnect`, `seed`, `unknown`, `memory`, `collision`, `permissions`, `boot-view`, `preflight`, `truncate`, puis `truncate-commit`. `commit_probes.py` conserve les commandes SQL/grants exactes du dernier contre-exemple durable. Le [protocole reproductible](../testing/evidence/L3-I3-CT/reproduction/README.md) donne l'ordre complet.

Autres commandes : `java -version`, `./mvnw -version`, `rg`, `cat`, `sed`, `nl`, métadonnées PostgreSQL par `docker exec ... psql`, `docker image inspect`, analyse des logs serveur **en mémoire avec expurgation avant export**, `git diff --check`, `git diff --exit-code`, `git status --porcelain`, collecte SHA-256 et copie des seuls rapports ciblés.

### Qualité et limites du banc

- `FOR UPDATE NOWAIT` depuis une connexion indépendante établit la protection effective : `55P03` pendant le verrou, acquisition possible après résolution. L'absence d'exception Java n'est jamais l'oracle du verrou.
- Les courses utilisent barrières et arête serveur `pg_blocking_pids`, pas un délai d'attente comme preuve de contention. Les bornes temporelles servent au watchdog.
- Les premières erreurs du banc ont été corrigées hors dépôt : capture lambda non finale, déclaration d'exception, consommation du résultat de `SET ROLE`. La première observation SET ROLE `02000` était une erreur de lecture du banc ; le résultat final est **accès possible**.
- Un essai de réexécution de `grant-l2-lifecycle.sql` après extension Discovery a refusé les droits techniques d'INSERT déjà étendus. C'est la différence des profils livrés, pas un finding Identity. Les contre-exemples finaux réexécutent les scripts combinés pertinents `grant-l2-discovery.sql` et `grant-l2-worker-dev.sql`.
- La panne UNKNOWN indépendante injecte une SQLException `08006` après retour du vrai `Connection.commit()` ; ce n'est pas une coupure TCP. La coupure avant COMMIT du proxy protocolaire existant a été lue, mais n'a pas été fraîchement exécutée ici.

## 4. Matrice des invariants et contre-exemples

### Axe A — protections EUI

| Invariant attendu | Contournement éprouvé | Observation indépendante | Conclusion |
|---|---|---|---|
| A1. Une protection Java désigne l'unité physique courante | Holder JDBC prélié, conservé entre deux transactions | Même holder/PID, XID différent ; `current` accepté et NOWAIT externe acquis en T2 | **Falsifié sous cette composition JDBC** ; CT-001 |
| A2. Deux transactions ordinaires successives ne partagent pas la protection | Hikari max=1, même thread/connexion | PID 218, holders différents, XID 944 puis 946 ; verrou physique dans chacune, libéré après commit | Confirmé dans ce chemin |
| A3. Rollback n'autorise pas la tentative suivante sans verrou | Erreur après acquisition, puis `current`, puis nouvelle acquisition | Rollback confirmé ; verrou libéré ; `current` suivant refusé ; réacquisition réellement protégée | Confirmé dans le chemin ordinaire testé |
| A4. UNKNOWN n'est pas une protection réutilisable | Vraie unité Identity committée, acquittement JDBC perdu, R9 | UNKNOWN ; R9 COMPLETED ; exactement un User/état/résultat/complétion ; appel sans lock suivant refusé | Confirmé pour perte après commit injectée ; avant commit non démontré indépendamment |
| A5. Recyclage de threads n'importe pas une protection d'une autre tentative | Exécuteur fixe à deux threads et tentatives successives | Appel sans lock sur second thread refusé ; état du thread Spring nettoyé | Confirmé sur ces interleavings, pas une endurance longue durée |
| A6. Connexion perdue/remplacée ne garde pas une protection valide | `pg_terminate_backend` après verrou | Tentative UNKNOWN ; nouveau PID ; `current` sans lock refusé ; nouvelle acquisition protégée | Confirmé sur perte de backend forcée |
| A7. Deux transactions concurrentes ne sont pas confondues | Deux threads, même autorité Java et deux connexions | Arête serveur 233→232 ; attente réelle puis protection du successeur ; pas d'autorisation croisée | Confirmé sur le scénario |
| A8. Le registre ne conserve pas fortement les holders terminés | 300 transactions, références faibles témoins et GC | 140 entrées avant observation finale, 0 holder vivant / 0 entrée après collecte | Aucune fuite forte observée ; nettoyage dépend de GC et accès au WeakHashMap |
| A9. La composition réelle protège les transactions | Manager JPA extrait des Resources du vrai Worker | PID 722 ; XID 1382/1384 ; holders distincts ; NOWAIT 55P03 ; holder prélié refusé avant callback | Confirmé dans la composition livrée testée |
| A10. `current`/`apply` exigent l'acquisition de l'unité | Appels directs sans acquisition | Les deux refusés avec rollback dans le chemin ordinaire ; exception A1 pour `current` | Garantie conditionnelle, pas universelle |

### Axe B — refus PostgreSQL, refus applicatif et accès réel

Les grants adversariaux sont retirés entre scénarios. « Préflight refusé » ne signifie jamais « PostgreSQL a empêché la lecture ».

| Scénario | PostgreSQL | Préflight / scripts | Conclusion |
|---|---|---|---|
| READ direct `binding_token`, état normal, USAGE ajouté pour isoler l'ACL table | **42501** | Profil normal accepté | Correction directe confirmée |
| READ contexte d'admission, état normal | **42501** | Profil normal accepté | Confidentialité directe confirmée |
| READ via fonction Identity invoker livrée | **42501** | Profil normal accepté | EXECUTE PUBLIC ne confère pas le SELECT sous-jacent |
| PUBLIC SELECT table puis colonne `binding_token` | **Accès possible**, agrégat non nul | Refus PUBLIC | Détection confirmée ; ACL excessive reste exploitable tant qu'elle existe |
| SELECT direct accordé au READ | **Accès possible** | Refus READ→WRITE | Détection confirmée |
| Rôle READ héritant d'un rôle lecteur supplémentaire | **Accès possible** | Refus membership | Détection confirmée dans le cluster testé |
| READ NOINHERIT, puis SET ROLE vers rôle lecteur avec USAGE | Direct **42501**, après SET ROLE **accès possible** | Refus membership | SET ROLE n'est pas neutralisé par NOINHERIT ; garde correcte dans ce cluster |
| Vue propriétaire exposant l'historique token | **Accès possible sur données non vides** | **Accepté ; vrai Worker démarré** | **Falsifié : CT-002** |
| Vue propriétaire exposant demande/token initiateur/provenance | **Accès possible** | **Accepté** | **Falsifié : CT-002** |
| SECURITY DEFINER dans autre schéma, SELECT qualifié, EXECUTE PUBLIC | **Accès possible** | **Accepté** | **Falsifié : CT-002** |
| UPDATE histoire par READ, Worker, injecteur, réactivateur, sans excès | **42501 dans les quatre cas** | Profil normal accepté | Moindre privilège initial confirmé |
| Admission d'une demande par Worker | **42501** | Profil normal accepté | Séparation admission/exécution confirmée |
| Réactivateur / injecteur avec TRUNCATE ajouté sur événement Identity | **TRUNCATE et COMMIT possibles** | **Scripts combinés et préflight acceptés** | **Falsifié : CT-003** |
| Collision réelle de token historique | Java : graphe d'exception expurgé ; serveur : DETAIL contient token test | Aucun contrôle livrant une politique de logs serveur | **Absence absolue de journalisation falsifiée : CT-004** |

## 5. Résultats matériels des tests indépendants

### Durée de vie et témoins physiques

Les logs `normal-authorized.log`, `retained-authorized.log`, `concurrent-authorized.log`, `reconnect-authorized.log`, `unknown-final.log`, `memory-authorized.log` et `boot-view-jpa.log` conservent les témoins. Aucun token n'est imprimé.

- **Ordinaire JDBC/Hikari :** T1 holder `1604247316`, PID `218`, XID `944` ; T2 holder `1201466784`, même PID, XID `946`. Sonde externe refusée sous verrou, puis autorisée après commit.
- **Contre-exemple JDBC :** T1 et T2 holder `1764996806`, même PID `227`, XID `951` puis `953`. T1 protège physiquement ; après commit, T2 accepte `current(KEY)` et la sonde externe acquiert le verrou. La tentative de réacquisition en T3 est au contraire refusée par « one complete set », car l'entrée ancienne existe encore.
- **Concurrence :** le second thread ne peut utiliser la protection du premier. Lors de sa propre acquisition, l'arête `233 → 232` est observée par PostgreSQL avant libération de la barrière.
- **Reconnexion :** arrêt serveur du backend pendant la tentative ; l'owner rend UNKNOWN et la connexion remplaçante n'autorise pas `current` sans acquisition. Le nouveau PID observé est `240`.
- **UNKNOWN + R9 terminal :** vrai commit de User/EUI/résultat/publication, puis exception d'acquittement injectée. PID `777`, XID `1389`, holder `1278002745` ; après R9, nouvelle tentative holder `1244867001`, XID `1391`, même PID. R9 COMPLETED et cardinalités exactes unitaires. Cette variante ne certifie pas le protocole Engine/slot, le banc ne crée pas de claim pour cette unité.
- **Manager réel du Worker :** JPA refuse la ressource JDBC préliée avant le callback. Les deux transactions normales au PID `722` prennent réellement le verrou et utilisent des holders distincts.
- **Mémoire :** la valeur du registre contient les clés EUI et leurs IDs, sans référence au holder ; le cycle fort clé→valeur→clé suspecté n'est pas présent. Après 300 transactions et GC observée, les 300 références faibles témoins sont mortes et le registre est vide. Pas de preuve de borne de mémoire instantanée sans GC, ni d'endurance à grande charge.

### ACL et objets SQL

Les sept tables V6 appartiennent au propriétaire administratif. Les ACL initiales concordent avec les scripts : SELECT du runtime WRITE, INSERT sur six tables, UPDATE colonne `status` / `current_version`, aucun INSERT de demande pour le runtime. L'injecteur peut lire/insérer les demandes et lire les colonnes nécessaires aux vérifications invoker ; il ne lit pas l'historique `binding_token`. Le rôle réactivation n'a initialement aucun droit Identity.

Business et Control ont le **même principal SQL** : leur accès au token est donc autorisé par construction. Le propriétaire du schéma peut également lire les tokens ; ce n'est pas une fuite démontrée à un rôle non autorisé. L'injecteur voit les tokens déclarés des demandes grâce à son SELECT de demande, ce qui doit rester dans la frontière DEV de confiance ; aucun isolement par demande n'est assuré par ces ACL.

V6 crée **14 fonctions invoker**, aucune SECURITY DEFINER ; les ACL de fonction nulles laissent EXECUTE PUBLIC par défaut. L'appel READ indépendant à la fonction d'unité est refusé par les SELECT invoker sous-jacents. Aucune vue ni séquence propre à Identity n'est créée par V6. Les huit séquences présentes dans le schéma proviennent de Pot ; WRITE/READ n'ont pas USAGE/SELECT/UPDATE sur elles dans le banc. Aucun contournement Identity par séquence n'est établi.

Les fonctions V6 utilisent des noms non qualifiés sans `SET search_path` local. Dans le Worker livré, la configuration exige un unique `currentSchema`, et les rôles non propriétaires ne peuvent créer ni dans ce schéma ni en TEMP ; aucun détournement de résolution de nom n'est reproduit dans cette configuration. Le SECURITY DEFINER adversarial réussit avec des noms entièrement qualifiés : sa fuite ne dépend pas d'une injection de search_path.

**Preuve durable de mutation interdite après excès de grant :**

```text
grant-l2-discovery.sql : accepté
grant-l2-worker-dev.sql : accepté
préflight : PASS
audit_a : TRUNCATE v3_identity_event, COMMIT ; lignes 4 → 0 ; positions conservées = 6
grant-l2-discovery.sql : accepté
grant-l2-worker-dev.sql : accepté
préflight : PASS
audit_i : TRUNCATE v3_identity_completion_event, COMMIT ; lignes 2 → 0 ; positions conservées = 6
```

Ces deux derniers tests détruisent volontairement le jeu **synthétique** du serveur jetable. Les premières sondes avaient fait rollback ; la relecture indépendante après les commits élimine le doute sur les contraintes différées. Les triggers d'immuabilité UPDATE/DELETE ne protègent pas TRUNCATE.

### Confidentialité des diagnostics

Une collision UNIQUE réelle passe par `JdbcIdentityAuthority.apply`. La nouvelle exception Java, ses causes et suppressed ne contiennent pas le token synthétique ; SQLSTATE `23505` est conservé et le rollback confirmé. En revanche le journal serveur comporte un DETAIL `Key (binding_token)=([REDACTED_TEST_TOKEN]) already exists`.

Paramètres constatés : `log_min_error_statement=error`, `log_error_verbosity=default`, `log_parameter_max_length_on_error=0`. Le dernier paramètre ne supprime donc pas le token de la DETAIL d'unicité. Le journal brut n'a pas été exporté ; seule une ligne expurgée et l'observation de présence sont conservées.

## 6. Findings hiérarchisés

Les chemins ci-dessous sont relatifs au checkout isolé du SHA. Les numéros désignent les lignes de ce SHA.

### AUD-L3-I3-CT-002 — MAJOR — Exposition indirecte acceptée par le préflight

**Nature : défaut démontré, conditionné par un objet/grant administratif excessif.**

**Invariant :** un principal runtime READ ne doit pas accéder aux tokens WRITE ni à la provenance sensible via un profil que le préflight accepte comme sûr.

**Localisation :** [V3BootstrapGuard.java:403](https://github.com/kartaguez/pocoma/blob/2b3567b1f77051c399772ee7ac787f7dd61ba407/app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3BootstrapGuard.java#L403) — lignes 403–429 : privilèges du READ sur les sept tables seulement ; 630–637 : recherche SECURITY DEFINER limitée à `current_schema()` ; 698–733 : inventaire `pg_tables`, sans les vues. Même restriction de schéma dans [grant-l2-discovery.sql:115](https://github.com/kartaguez/pocoma/blob/2b3567b1f77051c399772ee7ac787f7dd61ba407/scripts/v3/grant-l2-discovery.sql#L115), contrôles READ limités aux tables aux lignes 147–159.

**Reproduction :** sur le banc correctement préparé, accorder USAGE du schéma à READ ; créer en propriétaire une vue `SELECT binding_token FROM audit.v3_eui_version`, puis SELECT de cette vue à READ. Variante : vue sur `v3_identity_request(binding_token,admission_context)`. Variante indépendante : fonction SQL SECURITY DEFINER dans `audit_aux`, SELECT qualifié sur l'histoire, USAGE du schéma à READ et EXECUTE PUBLIC par défaut.

**Attendu :** refus du profil excessif avant démarrage ; PostgreSQL doit refuser la lecture dans le profil de moindre privilège retenu. **Observé :** agrégats non nuls possibles sous READ, zéro droit direct sur les tables, préflight accepté. Le vrai `PocomaCommandConsumptionWorkerApplication` démarre avec la vue exposée et READ peut toujours la lire.

**Impact :** divulgation de l'historique des tokens et du token initiateur/provenance des demandes par un principal déclaré READ. Une mauvaise configuration analogue au grant PUBLIC déjà corrigé échappe à la garde. Aucun octroi autonome par l'attaquant READ n'est démontré.

**Correction minimale proposée, non implémentée :** étendre l'inventaire refusé/autorisé aux vues, vues matérialisées et fonctions accessibles au principal READ sur la base WRITE, y compris hors du schéma courant ; vérifier la fermeture de privilèges/dépendances des objets capables de lire les tables sensibles. Dans ce profil DEV fermé, refuser explicitement tout objet intermédiaire exposant Identity et toute fonction SECURITY DEFINER non autorisée est une option simple. Retirer les grants excessifs au provisionnement ; le refus applicatif ne remplace pas ces ACL.

**Non-régression :** données non vides ; trois variantes ci-dessus ; SELECT sous vraie connexion READ ; démarrage des deux racines ; vérification du rôle propriétaire de la vue et des options security_invoker ; oracle indépendant confirmant absence de token retourné après correction.

### AUD-L3-I3-CT-003 — MAJOR — TRUNCATE Identity des rôles auxiliaires non contrôlé

**Nature : défaut démontré, avec commits destructeurs sur données de test.**

**Invariant :** réactivateur et injecteur ne peuvent muter l'historique Identity ; un excès de privilèges préexistant doit être refusé par les scripts/préflight.

**Localisation :** [grant-l2-discovery.sql:134](https://github.com/kartaguez/pocoma/blob/2b3567b1f77051c399772ee7ac787f7dd61ba407/scripts/v3/grant-l2-discovery.sql#L134) — lignes 134–138 : contrôle pour `a` des seuls privilèges de colonnes SELECT/INSERT/UPDATE/REFERENCES, sans DELETE/TRUNCATE/TRIGGER de table ; [grant-l2-worker-dev.sql:55](https://github.com/kartaguez/pocoma/blob/2b3567b1f77051c399772ee7ac787f7dd61ba407/scripts/v3/grant-l2-worker-dev.sql#L55) — lignes 55–62 : DELETE/TRUNCATE/TRIGGER contrôlés seulement sur la demande, sans couverture complète des tables d'événements pour l'injecteur. Le runtime `V3BootstrapGuard.java:403–429,491–537` connaît le runtime WRITE et READ, pas ces deux rôles auxiliaires. V6 `:101–114` protège l'immutabilité par UPDATE/DELETE, sans trigger TRUNCATE.

**Reproduction :** ajouter `GRANT TRUNCATE ON audit.v3_identity_event TO audit_a`, réexécuter les scripts combinés, préflight, puis TRUNCATE/COMMIT sous login `audit_a`. Refaire avec `audit_i` et `v3_identity_completion_event`.

**Attendu :** les scripts refusent l'excès ; dans un profil conforme, PostgreSQL refuse la mutation. **Observé :** scripts exit 0, préflight PASS, commits possibles ; relecture admin indépendante : 4 événements mutation et 2 complétions supprimés, 6 positions conservées. Les contrôles différés ne refusent pas ces commits.

**Impact :** destruction durable de l'histoire événementielle et perte de cohérence entre événements, demandes terminales et positions. Les droits livrés initialement restent corrects ; le défaut est l'acceptation silencieuse de cet excès administratif.

**Correction minimale proposée, non implémentée :** contrôler les privilèges effectifs de table DELETE/TRUNCATE/TRIGGER sur **les sept tables** pour réactivateur et injecteur, en plus des droits de colonne et droits hérités ; vérifier l'allowlist exhaustive de l'injecteur, notamment les deux tables d'événements. Définir clairement quelle étape administrative certifie ces rôles puisque le Worker n'en connaît pas l'identité. Ne pas réécrire V6 publiée ; une défense SQL complémentaire éventuelle relève d'une migration additive.

**Non-régression :** matrice rôle×table×DELETE/TRUNCATE/TRIGGER, grants directs et hérités, refus de réexécution ; négatif TRUNCATE avec COMMIT sur données non vides ; contrôle indépendant des comptes événements/positions et absence de dégradation silencieuse.

### AUD-L3-I3-CT-001 — MINOR — Une clé holder peut survivre à une unité physique JDBC

**Nature : défaut de garde démontré dans une composition JDBC acceptée ; exploitation du Worker livré non démontrée.**

**Invariant :** `current` et `apply` n'utilisent que les protections acquises par la transaction courante ; la durée de vie de la clé Java ne doit pas dépasser cette unité.

**Localisation :** [JdbcIdentityAuthority.java:176](https://github.com/kartaguez/pocoma/blob/2b3567b1f77051c399772ee7ac787f7dd61ba407/app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/JdbcIdentityAuthority.java#L176), également lignes 27–30, 50–53, 75–76. Le registre est indexé par ressource Spring ; la présence d'une entrée est assimilée à une acquisition actuelle. [SpringAttemptTransactions.java:170](https://github.com/kartaguez/pocoma/blob/2b3567b1f77051c399772ee7ac787f7dd61ba407/app/infra-tx-spring/src/main/java/com/kartaguez/pocoma/infra/tx/spring/v3/SpringAttemptTransactions.java#L170) — lignes 170–177 : refus d'une transaction/synchronisation ambiante, pas d'un holder prélié hors transaction. La nouvelle Unit est bien distincte (194–199), mais son identité n'est pas la clé des protections.

**Reproduction :** emprunter une connexion Hikari ; lier `new ConnectionHolder(connection)` à la datasource par `TransactionSynchronizationManager.bindResource` hors transaction ; owner `DataSourceTransactionManager`, nesting désactivé. T1 acquiert KEY puis commit. T2, sans acquisition, appelle `current(KEY)`. Garder le holder lié entre T1 et T2.

**Attendu :** refus de T2 ou refus initial de cette composition ; une lecture autoritative actuelle doit être couverte par le verrou de T2. **Observé :** même holder/PID, deux XID ; `current` accepté alors qu'une connexion indépendante acquiert KEY en NOWAIT. Une nouvelle acquisition complète est ensuite refusée à cause de l'entrée périmée.

**Impact :** mauvaise autorisation de la primitive de lecture protégée et indisponibilité si un appelant conserve un holder à travers des unités JDBC. **Limitation déterminante :** `ExecuteIdentityRequest.finalizeIn` appelle toujours `lockInCanonicalOrder` avant `current`, donc cette voie échoue sur l'entrée périmée ; elle ne contourne pas silencieusement le participant. Le vrai Worker WRITE utilise JPA (`V3ConsumptionWorkerConfiguration.java:325–344`) ; son manager a été éprouvé et refuse le holder prélié avant callback. Aucune course d'autorisation métier effective du Worker livré n'est donc revendiquée.

**Correction minimale proposée, non implémentée :** soit refuser une ressource JDBC préliée à l'entrée de l'owner dans ce profil, soit associer les protections à l'identité de l'Unit existante plutôt qu'au holder seul. Conserver la discipline d'acquisition du jeu complet et le poisoning ; ne pas compter sur GC pour expirer une autorisation transactionnelle.

**Non-régression :** holder prélié après commit, rollback et UNKNOWN, nouvelles Units avec même connexion ; appels `current` et `apply` sans acquisition courante ; sonde NOWAIT indépendante ; variante JPA prouvant le refus avant callback et absence de changement du parcours ordinaire.

### AUD-L3-I3-CT-004 — MINOR — Le sanitizer Java ne couvre pas le diagnostic serveur

**Nature : journalisation de token test démontrée ; accès non autorisé aux logs non démontré.**

**Invariant :** absence de valeur de token dans les logs/diagnostics exportables ; le rapport I3 décrit la confidentialité des erreurs mais sa preuve Java ne couvre pas le serveur.

**Localisation :** [JdbcIdentityAuthority.java:206](https://github.com/kartaguez/pocoma/blob/2b3567b1f77051c399772ee7ac787f7dd61ba407/app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/JdbcIdentityAuthority.java#L206) — lignes 206–218 : exception expurgée après réception ; [V6 Identity:52](https://github.com/kartaguez/pocoma/blob/2b3567b1f77051c399772ee7ac787f7dd61ba407/app/infra-persistence-primary-jpa/src/main/resources/db/v3-identity/migration/V6__identity_authority_and_requests.sql#L52) déclare UNIQUE sur `binding_token`. `JdbcIdentityRequestStore.java:42–54` applique une stratégie similaire aux erreurs de demande. Le runbook ne fixe aucune politique PostgreSQL évitant la DETAIL d'unicité sensible.

**Reproduction :** générer deux tentatives Registration utilisant le même token **synthétique** ; passer la seconde par l'autorité Java ; contrôler en mémoire le graphe d'exception et le journal du PostgreSQL jetable, sans exporter la valeur.

**Attendu :** aucune présence du marqueur test dans les diagnostics conservés. **Observé :** exception Java expurgée et rollback ; une occurrence du marqueur dans la DETAIL serveur, même avec `log_parameter_max_length_on_error=0`.

**Impact :** copie supplémentaire d'un secret dans les journaux serveur et leur éventuelle collecte/rétention. Aucun accès de READ à ces journaux ni incident de production n'est affirmé ; ce constat ne doit pas être présenté comme une exfiltration authentifiée.

**Correction minimale proposée, non implémentée :** définir et vérifier une politique serveur/collecteur qui supprime les DETAILS et paramètres sensibles sur ces erreurs, tout en conservant SQLSTATE et corrélation non sensible. Qualifier explicitement l'effet des réglages retenus ; le seul sanitizer Java ou la seule borne de paramètres ne suffit pas.

**Non-régression :** collision UNIQUE et violation CHECK impliquant une demande sensible ; collecte Java, PostgreSQL et collecteur réel ; présence de diagnostic technique mais zéro token/contexte brut, sans désactiver les contraintes ni abaisser les oracles métier.

## 7. Réserves et limites de preuve

1. L'audit est une recherche de contre-exemples sur deux axes, pas une certification de toutes les garanties Identity ni de V3. Aucun PASS global ID-01/ID-02/CON/EVT n'est prononcé.
2. Les deux défauts majeurs nécessitent des privilèges/objets administratifs ajoutés au profil normal. Le banc prouve un refus incomplet des configurations excessives ; il ne prouve pas une escalade autonome depuis les ACL neuves.
3. Deux bases dans un même serveur et catalogue de rôles ont été éprouvées. Des serveurs séparés avec rôles homonymes et appartenances divergentes ne sont pas couverts. Les mécanismes d'héritage/SET ROLE sont démontrés pour PostgreSQL 17.11 et ce cluster.
4. La perte d'acquittement indépendante est injectée au niveau JDBC après un vrai commit. Le proxy réseau existant avant/après COMMIT n'a pas été rejoué. Les erreurs `UNKNOWN` ne sont jamais assimilées par supposition à un rollback.
5. La preuve mémoire de 300 unités montre la collectabilité effective, pas une borne d'usage à long terme ou sans GC. Le holder prélié reste volontairement fortement accessible : WeakHashMap ne résout pas son problème de durée de vie.
6. Le cas prélié JDBC est limité comme décrit dans CT-001. Le maintien des verrous jusqu'à la résolution est confirmé dans les chemins ordinaires testés, pas pour toute composition Spring imaginable ou une manipulation manuelle de connexion au milieu d'un callback.
7. La mutation TRUNCATE a été committée sur les événements, pas sur chacune des sept tables. L'omission d'ACL est localisée ; les FK peuvent limiter d'autres TRUNCATE isolés. Aucun résultat n'est généralisé à une table non exercée.
8. Le vrai Worker a démarré depuis ses classes compilées au SHA, avec ses quatre pools et son manager JPA ; aucun nouveau packaging exec jar ni nouveau démarrage monolithe indépendant. Les six tests frais comprennent les scénarios de restart livrés du Worker, pas une campagne d'endurance supplémentaire.
9. Seuls tokens de test et données synthétiques ont été utilisés. Les rapports exposent booléens, cardinalités, PID/XID et diagnostics expurgés ; aucune vraie valeur de token n'est exportée. Les fichiers de code du banc contiennent volontairement un marqueur de test constant, pas un secret utilisateur.
10. Les journaux d'essais échoués du banc sont conservés ; les résultats finaux ne réécrivent pas leurs observations. Aucun FAIL historique L2 n'a été relancé pour rechercher un PASS.

## 8. Recommandation d'arbitrage I3 / I4

**FAIL — CORRECTIONS REQUIRED sur le périmètre strict des deux axes.**

Avant décision I4, traiter CT-002 et CT-003 puis demander une contre-vérification indépendante des mêmes témoins non vides, sous les véritables rôles, avec démarrage des racines concernées. Statuer explicitement sur la restriction de composition JDBC de CT-001 et sur la politique de logs de CT-004. Aucune correction n'a été réalisée dans cet audit.

L'axe transactionnel ne justifie pas à lui seul un rejet du Worker JPA livré : ses protections ordinaires sont confirmées dans les scénarios réalisés. L'axe sécurité ne peut recevoir un PASS sans réserve : deux profils excessifs acceptés donnent effectivement lecture sensible ou destruction durable.

Ce verdict **ne transforme pas COMMAND + Architecture en PASS**, ne lève pas `i5_r12` ni les réserves antérieures, ne certifie pas un runtime de production et n'autorise ni n'entame I4/L4/L5. L'arbitrage humain reste à conduire après examen contradictoire du présent rapport et des scripts joints.
