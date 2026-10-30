# Pocoma V3 — L3/I3 — IdentityRequest et Consumption

Date : 2026-10-30. Code gelé final : `3ae0966baacc81ffc23aac9c1d9c2f29af578fcf`.
Départ : `6f0cf7d98f402ad29400e879c1efee75f147537a`, local et distant identiques.
Branche : `feat/v3-architecture`, worktree propre au départ
`/private/tmp/pocoma-v3-postaud`. Le checkout initial `feat/runtime-monolith`
(`90a3e9bc`) et ses travaux préexistants ont été préservés ; aucun changement de
branche, nettoyage ou reset n'y a été effectué. Les worktrees I3 préexistants
étaient des travaux **L2/I3**, distincts de ce lot.

**R-ID : runtime interne opérationnel démontré avec le jar corrigé.**
**Gate canonique finale : FAIL (`i5_r12`) ; aucun PASS canonique prononcé.**
L'acceptation indépendante/humaine de ce lot reste distincte de son implémentation.
Aucun GO I4/I5, R-ID-POT, READ L4, HTTP/consentement L5 ou production.

## 1. Verification scope — déclaré avant implémentation

- Impact attendu : participant Identity, requête/résultat/R9/publications JDBC,
  sources et mappings Discovery, composition des racines existantes, gardes,
  grants et admission DEV contrôlée.
- Slice primaire : **COMMAND**. Racine complémentaire : **runtime-monolith**.
  Secondaires canoniques : **NONE**. EVENT/PROJECTION/BINDING/WEB ne changent
  pas de comportement productif ; leur compilation/exécution éventuelle dans
  la fermeture Maven n'est pas un franchissement de slice.
- Gate Architecture : **REQUIRED**, pour arêtes POM, writers et activation.
- Full reactor : **NOT REQUIRED / NOT RUN**, raccord métier borné I3 ; la prochaine
  obligation globale relève d'une Wave majeure/main/release selon la politique.
- DB : **CURRENT V3 WRITE V1–V6**, READ V1 technique pour les probes. Aucune
  baseline V2 TRUSTED inventée, aucune campagne historique séparée. Les migrations
  historiques effectivement exécutées transitivement sont conservées dans les logs.
- Interdit : autorité Pot I4, qualification I5, projections/HTTP, nouveau Worker,
  scheduler, transaction manager/owner runtime, redesign renewal, nouveaux IDs/hash.
- Escalade : nouvelle capacité productive, owner/routage ou dépendance imprévue
  à déclarer avant extension. **Franchissement inattendu observé : NONE.**

La déclaration initiale a été écrite avant le premier changement productif dans
ce même fichier et annoncée dans la conversation, conformément à la
[politique Reactor](../testing/Reactor_Verification_Policy.md).
Le [plan L3](L3_Identity_Inventory_and_Implementation_Plan.md), son §6/I3,
SPEC-02/03/05/06/10 et la [décision I2](L3_I2_Acceptance_Decision_2026-10-30.md)
restent les autorités. Les réserves historiques **i5_r05 / i5_r12 restent OPEN**.

## 2. Livraison et inventaire

Les six composants prévus sont livrés : `ExecuteIdentityRequest`,
`IdentityRequestJson`, `JdbcIdentityRequestStore`, `JdbcIdentityRequestObservation`,
`JdbcIdentityRequestDiscoveryReferences`, `V3IdentityRequestComposition`.

| Ensemble | Changement effectif |
|---|---|
| Participant | Préparation de l'intention seule ; relecture finale, ancres canoniques, décisions Registration/Link/Unlink, effets, résultat/TERMINAL, capacité R1 puis publication du résultat effectif |
| JDBC | Insertion PENDING interne idempotente sans slot ; intention/provenance immuables ; vérification des effets exacts et writer_xid avant completion ; observation R9 sans réparation |
| Publication | User v1 puis EUI pour Registration ; EUI pour Link/Unlink ; rejet : completion seulement ; flush/contraintes structurelles nommées, préflight terminal, closePublication, compteurs IDENTITY puis IDENTITY_COMPLETION et positions denses |
| Discovery | Source burnable bornée et revalidation SKIP LOCKED ; mappings fermés des deux nouvelles familles ; User examiné mais ignoré par CurrentBinding, K avance effectivement sur ce préfixe |
| Composition | IdentityRequest nommé dans le Worker existant et les deux racines ; ressources Business/Control, capacité, Engine et owner L2 réutilisés ; mode real explicite, fixture et Command réel refusés avant I4 |
| POM | Dépendance directe optionnelle composition→engine-consume-registration ; runtime Command→engine-consume-registration/contracts-registration ; aucun nouveau module ni changement du POM monolithe |
| Administration/DEV | V6 sélectionnée explicitement ; grants séparés runtime/injecteur/réactivation ; fichiers d'intention privés, inspection terminale bornée, helper confidentiel de token, scénario reproductible ; aucun DDL au démarrage/restart |
| Tests | Toutes les classes prévues au §6/I3, preuve monolithe complémentaire, vérifications d'architecture et du codec d'intention DEV ; supports de tests L2 réemployés |

L'[inventaire nominatif du code gelé](../testing/evidence/L3-I3/change-inventory.json)
donne chaque chemin, statut et SHA-256 au commit de code. L'[inventaire complet
de livraison](../testing/evidence/L3-I3/delivery-changes.tsv) inclut tous les chemins
depuis le départ, documents/index et archives compris ; il est contrôlé contre
`git diff 6f0cf7d9..HEAD --name-status` après le commit final. Les index mis à jour
sont `docs/README.md`, le plan directeur, le canon/roadmap et le plan L3 ; leurs
arbitrages et résultats historiques restent conservés.

Les moteurs/owners L2, RoutingV1, catalogue et UUID canoniques, migrations V1–V6,
`bootstrap-l2-routing.sql` et `reactivate-l2-slot.sql` sont inchangés octet pour
octet par rapport au départ. Cela est contrôlé dans le manifeste. Ni contrainte
V6 ni poisoning ni protocole de suffixe n'a été assoupli.

Trois corrections/ajustements bornés sont explicités :

1. Le sanitizer de `JdbcIdentityAuthority` conserve maintenant le SQLSTATE dans
   une **nouvelle SQLException expurgée**, sans DETAIL, paramètres ni cause JDBC
   originale. Le classement technique L2 peut ainsi voir le SQLSTATE ; le test
   de collision réelle de token prouve rollback, budget unique, absence de rejet
   et de divulgation. Aucun mécanisme L2 n'est modifié.
2. L'empreinte DEV initiale des octets du fichier a été remplacée par SHA-256
   d'un encodage des **champs décodés**, délimités par leurs longueurs UTF-8
   (null distingué). Deux fichiers de contenu physique différent représentent
   désormais exactement la même intention/provenance ; opération/token modifiés
   changent l'empreinte. Le contre-exemple est conservé dans les tests et le jar.

3. **ACL Identity, preuve contradictoire** : le jar `970609cd` acceptait un grant
   `PUBLIC SELECT(binding_token)` ; après USAGE du schéma, un principal READ
   pouvait sélectionner cette colonne WRITE. La [preuve avant correction](../testing/evidence/L3-I3/smoke-pocoma-l3-i3-jar-d1xrz3ru.json)
   utilise `count(binding_token)` sur une table vide, sans aucune valeur de token.
   Les gardes et scripts refusent maintenant tout droit PUBLIC sur les sept
   tables Identity (y compris par colonne), ainsi que tout droit Identity WRITE
   du principal runtime READ. La préparation transmet explicitement ce principal
   (`identity_read_role`) ; elle exige déjà quatre rôles distincts. Les deux
   racines et la réexécution du script Discovery refusent les trois variantes :
   SELECT public par table, SELECT public par colonne, SELECT direct du rôle READ.
   Aucun droit n'est retiré ou réparé automatiquement ; aucun contrôle technique
   L2 existant ni contrainte V6 n'est modifié.

Un ancien négatif Discovery qui désignait IDENTITY_REQUEST comme non livré
utilise maintenant IDENTITY_EVENT, famille effectivement interdite à la factory
**requests**. Le chemin IdentityRequest livré est exercé positivement par les
nouveaux tests. Aucun oracle temporel historique n'a été changé.

## 3. Preuves bornées et physiques

Environnement : Maven 3.9.14, Temurin Java 21, macOS/aarch64, PostgreSQL 17.11
sur image Docker épinglée par digest, connexions indépendantes. Les interleavings
utilisent barrières, attentes serveur, PID/XID et probes NOWAIT ; les deadlines
servent de bornes de watchdog, pas d'oracles de concurrence.

| Classe / méthodes | Preuve réellement exercée |
|---|---|
| ExecuteIdentityRequestTest (3 cas) | Préparation sans autorité/mutation, ordre effectif avant complete/publication, rejet terminal, faute technique propagée |
| IdentityRequestExecutionPostgresTest (13 cas) | Engine L2 réel ; trois opérations/histoire/self/last ; 28 coupures opération×frontière ; claims remplacés/expirés, horloge après attente slot, perte partition avec claim valide ; deux Engines concurrents ; usages invalides de completion ; retry N=3/Q/réactivation ; quatre observations R9 ; token collision technique expurgée |
| Même classe, l3_unknown_commit | Proxy protocole PostgreSQL réel : COMMIT/ReadyForQuery consommé après commit contre coupure avant commit ; UNKNOWN et observation durable distingués, aucune seconde mutation/token/User ni budget fictif |
| IdentityPublicationPostgresTest (8 cas) | Lots User→EUI, densité/rollback ; attentes FK dans le préfixe ; refus différé de position omise/mauvaise famille avec rollback intégral ; contraintes structurelles et faute interceptée avant compteur ; writer tardif condamné ; même PID/XID ; contention réelle des deux compteurs et protections NOWAIT |
| IdentityDiscoveryPostgresTest (3 cas) | PENDING sans slot, enrôlement unique, absence de réenrôlement terminal/checkpoint burnable, source/slot sans cycle, fenêtres des deux types Identity et trois complétions |
| IdentityPrivilegesPostgresTest (3 cas) | Idempotence/immutabilité, runtime sans admission ni mutation d'histoire/DELETE métier, vraie exécution sous droits restreints, refus source/owner étranger |
| V3IdentityRequestCompositionPostgresTest (3 cas) | Racine réelle, V6/grants par scripts livrés, opérations et restart ; négatifs mode/fixture/Command/catalogue/rôle/héritage/migration, PUBLIC table/colonne et accès READ→WRITE ; Discovery canonique READ technique K=2 pour User ignoré puis EUI, quatre partitions |
| V3IdentityRequestMonolithPostgresTest (2 cas) | Même raccord réel dans la seconde racine, opérations/arrêt/restart et préflight négatif |
| V3WorkerDevelopmentToolsTest (2 cas) | Empreinte canonique discriminante, provenance CONTROLLED_INTERNAL, fichier 0600 requis et symlink refusé |
| Architecture | 11 contrôles ciblés initiaux ; après correctif ACL, 144 contrôles structurels puis 18 cas PostgreSQL ccr/ept : les 28 classes du module sont couvertes en feedback explicitement filtré ; aucun transfert vers un PASS de la fermeture canonique |

Les 27 cas des quatre classes PostgreSQL de persistance I3 sont de nouvelles preuves Engine/Discovery,
**pas** des appels au harness d'exécution I2. Le support I2 est réutilisé pour
l'installation V6, les connexions, les digests et la collecte ; `run/finish` du
harness I2 ne sont pas utilisés par ces scénarios. Les tests de privilèges des
racines utilisent de vraies connexions sous les rôles login non propriétaires.
Les garanties génériques L2 non modifiées restent également exercées par la
fermeture canonique, avec leurs propres limites et résultats.

Les [traces et reçus](../testing/evidence/L3-I3/manifest.json) indiquent les
frontières, bloqueurs, résolutions et digests durables. Les traces ne contiennent
pas les valeurs des tokens. Certaines lignes de diagnostic physiques des anciens
logs sont expurgées ; le manifeste distingue SHA-256 original privé, données
archivées et compression. Aucune configuration contenant des credentials n'est
versionnée.

## 4. Jalon runtime R-ID

Le [runbook](L3_I3_Identity_Development_Runbook.md) décrit la configuration
explicite et les commandes. La preuve finale utilise le vrai exec jar corrigé,
la CLI `prepare`, les scripts V6/grants et `demo-identity`, avec credentials en
environnement et fichiers privés externes. Elle n'utilise aucun endpoint client.

[Reçu du jar corrigé](../testing/evidence/L3-I3/smoke-pocoma-l3-i3-jar-86_rn2_h.json) :

- préparation unique de deux bases neuves autorisées, WRITE V1–V6 et READ V1 technique ;
- deux Registrations, Link, autre-Unlink, re-Link, self-Unlink, dernière EUI,
  rejet terminal ; publications et observation R9 cohérentes ;
- retransmission d'une Registration terminale depuis un fichier reformaté :
  même demande/provenance, **zéro changement** du vecteur durable ;
- deux démarrages, deux arrêts avec callback de drain observé et sorties physiques ;
- même base sans prepare/reset ; une PENDING admise pendant l'arrêt est exécutée
  après restart ; les huit anciens terminaux sont relus sans effet supplémentaire.

SHA-256 du jar :
`37a5df9a03eb36dff2dac62b598139e1b06349354345f9487e4271d2923eb85f`.
Le jar précède le commit de son contenu inchangé ; la gate finale utilise le
même code committé `3ae0966b`. Le premier essai de banc a été FAIL sur le contrôle du chemin
physique `/var` vs `/private/var` à l'arrêt ; son reçu est conservé. Un second essai
avant correction du fingerprint était PASS opérationnel ; il n'est pas substitué
à la preuve du jar corrigé.

**R-ID opérationnel interne est démontré.** Ce constat n'est ni un PASS canonique
par substitution ni une acceptation indépendante. CONTROLLED_INTERNAL ne constitue
jamais un consentement externe : **ID-02/C-01 externe NOT RUN** ; projections READ,
HTTP et autorité Pot réelle absents de ce raccord. Les résultats/events/observations
publiés par ces outils ne donnent aucun token ni UserId destinataire.

## 5. Commandes et statuts de vérification

Toutes les commandes Maven sont exécutées depuis `app/`. Le [manifeste des
exécutions](../testing/evidence/L3-I3/manifest.json) conserve **chaque commande exacte**,
exit code, totaux de classes terminées, modules SKIPPED et logs, y compris les FAIL.

```sh
# Packaging borné, 33 cas (contribution ciblée, pas une gate canonique)
./mvnw -pl runtime-command-consumption-worker -am -Dtest=ExecuteIdentityRequestTest,IdentityRequestExecutionPostgresTest,IdentityPublicationPostgresTest,IdentityDiscoveryPostgresTest,IdentityPrivilegesPostgresTest,V3IdentityRequestCompositionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false package

# Correction du codec DEV, 2 cas discriminants et nouveau jar
./mvnw -pl runtime-command-consumption-worker -am -Dtest=V3WorkerDevelopmentToolsTest -Dsurefire.failIfNoSpecifiedTests=false package

# Gate finale non filtrée : COMMAND + racine monolithe + Architecture
./mvnw -pl runtime-command-consumption-worker,runtime-monolith,architecture-tests -am test
```

Le feedback précédent comprend les commandes filtrées de compilation, participant,
quatre classes PG, deux racines et architecture ciblée ; leurs paramètres intégraux
et raisons de correction sont archivés, sans fusionner leurs verdicts avec la gate.
Les premiers FAIL sont des erreurs du nouveau banc (inférence Java/capture lambda,
état AVAILABLE, type d'exception, manager avec nesting autorisé), corrigées en
respectant les gardes L2. Ils n'ont pas été effacés.

Une première gate non filtrée a été **interrompue volontairement, exit 143,
INCONCLUSIVE**, après 999 cas terminés sans failure/error/skip, pour corriger
l'empreinte d'intention. Ce n'est pas un PASS. Une deuxième gate porte sur les
nouveaux inputs `970609cd`. Cette exécution complète est **FAIL : 1 859 tests,
1 failure (`i5_r12`), 0 error, 1 skip historique**, durée 46:35 ; Architecture est
SKIPPED. Son [log](../testing/evidence/L3-I3/logs/canonical-before-acl.log.gz) est
conservé, avec son JUnit et ses observations temporelles. Le SKIP est
`TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness` :
propriété `step3b.measure` absente ; aucun test I3 n'est SKIPPED. Le correctif
ACL est un nouveau changement réel de préflight, prouvé
contradictoirement ; la dernière exécution porte sur `3ae0966b`. Aucune de ces
reprises n'est une relance inchangée destinée à obtenir un PASS probabiliste, et
le résultat historique n'est pas effacé.

Le feedback ACL utilise la commande `package` avec sélection explicite des deux
racines, de l'outil et des classes `architecture.*Test`/`architecture.v3.*Test` :
151 cas PASS, dont 144 structurels. Les cinq classes PG restantes (`ccr`/`ept`)
sont ensuite exécutées explicitement via `-pl architecture-tests -am`, 18 cas
PASS. Ce sont des contributions filtrées, pas le contournement de la fermeture
canonique. Le manifeste contient les deux commandes intégrales.

Autres contrôles réellement exécutés : Git branche/HEAD/worktrees/status/remote
et `ls-remote`, lectures des références, `git diff --check`, compilation syntaxique
Python sans cache, formatage des seuls Java modifiés, `./mvnw -version`,
`python3 -B scripts/v3/check-routing-vectors.py` (**21 vecteurs PASS**), scripts
administratifs et commandes de démarrage/scénario/arrêt du runbook sur le banc
jetable, vérification de restauration gzip et des empreintes des preuves. Un `jcmd
46137 Thread.print` en lecture seule a confirmé que `i5_w16` exécutait ses deux
fenêtres de cinq minutes ; aucune fenêtre ni aucun seuil n'a été raccourci.

**Gate finale sur `3ae0966b` : FAIL, exit 1, 47:31 minutes**, terminée le
2026-10-30 à 19:08:00 Europe/Paris : **1 859 tests, 1 failure, 0 error, 1 skip**.
L'unique failure est `V3WorkerResourcesPostgresTest.i5_r12:896`,
`runtime condition deadline`. Le skip historique reste l'exploratoire
`TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`.
Les **37 nouveaux cas I3 sont PASS, zéro skip**, avec JUnit fraîchement produits
par cette gate ; le monolithe est SUCCESS. Le module **Architecture est SKIPPED**
après l'échec amont : ses 162 cas en feedback filtré ne valent pas clôture de la
gate globale obligatoire. La clôture canonique demeure donc **NON ACQUISE**.
Le [log final](../testing/evidence/L3-I3/logs/canonical-final.log.gz), les JUnit
et les observations physiques temporelles de cette exécution sont conservés.
La gate inchangée n'est pas relancée pour rechercher un PASS.

Aucun seuil, réserve ou test historique n'est modifié pour obtenir un PASS. Aucun `-DskipTests`, `-amd`, filtre caché ou full reactor.
Les campagnes historiques séparées ne sont pas relancées ; leurs exécutions
transitives réelles restent visibles dans les logs.

## 6. Commits, publication et limites

- `f905a3c9b93d9aac8463dc76a786f8c30a345329` — implémentation I3, tests et déclaration initiale.
- `3629af4a2c5fe5ec7abc00af33ecbb74355a468f` — adaptation du négatif Discovery.
- `970609cd556b08addcec20d77e155144656763dd` — empreinte canonique DEV et contre-exemple.
- `3ae0966baacc81ffc23aac9c1d9c2f29af578fcf` — refus ACL PUBLIC et principal READ, scripts et contre-exemples des deux racines.
- Commit documentaire final : donné avec le SHA après publication, sans auto-référence circulaire.

Contrôle distant avant publication : `refs/heads/feat/v3-architecture` vaut
encore `6f0cf7d98f402ad29400e879c1efee75f147537a`. Les quatre commits de code
sont une avance linéaire. Ce rapport et les preuves constituent le cinquième
commit, documentaire seulement ; aucun input `app/`/`scripts/` ne diffère du
code gelé `3ae0966b`.

La publication autorisée utilise `git push origin HEAD:feat/v3-architecture`,
sans force. Pour éviter une auto-référence circulaire, le SHA du commit documentaire
et le résultat effectif du contrôle après push (HEAD distant/local, divergence,
propreté du worktree de livraison et préservation du checkout initial) sont donnés
dans le message de livraison. Ce rapport ne constitue pas une attestation anticipée
de réussite du push.

La clôture technique canonique dépend du résultat de la gate obligatoire, et
l'acceptation appartient à la revue humaine indépendante. **Les réserves i5_r05
et i5_r12 ne sont pas levées par I3, quel que soit le résultat d'une exécution
fraîche.** Aucun changement des budgets renewal/pools/charge L2, aucune
certification externe et **arrêt à I3, sans GO I4 implicite**.
