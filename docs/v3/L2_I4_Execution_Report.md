# Exécution L2 / I4 — Discovery

Résultat : **I4 LOCAL BOUNDED PASS**, code figé `0b08d3bf0bfce1334260cfd5249b13c3040a67bb`. Ce rapport ne constitue pas un audit indépendant. L2 global reste **INCONCLUSIVE** ; G1/G2/G3 ne sont pas franchis.

## Mandat et provenance

GO humain limité à I4 du [plan approuvé](L2_I4_Implementation_Plan.md), SHA de référence et HEAD initial `489259dbad757e3256572f8ce2041a187adeb38d`, branche `feat/v3-architecture`, dépôt `kartaguez/pocoma`. HEAD distant identique et divergence 0/0 au démarrage, aucun delta à intégrer depuis le SHA approuvé. Inventaire initial conservé dans [Initial-Git.txt](../testing/evidence/L2-I4/Initial-Git.txt).

Travail réalisé exclusivement dans `/private/tmp/pocoma-v3-postaud`, checkout I4 propre au départ. Le checkout principal `feat/runtime-monolith` à `90a3e9bc1a1fdd325d0c36e543824239cd85a431` contient des travaux préexistants, préservés. Les autres worktrees sont conservés. Aucun reset destructif, nettoyage ou force push.

## Frontière déclarée avant implémentation

Impact productif prévu et observé : Engine Discovery pur, trois ports réduits, trois adaptateurs JDBC, profil de privilèges et factories explicites inactives. Slice primaire **COMMAND**, secondaire canonique **NONE** ; preuves complémentaires READ JDBC et monolithe. Gate global architecture **REQUIRED** : nouveaux writers/ports et règles d’isolation à fermer. Full reactor **NOT REQUIRED**, non exécuté : étape bornée, sans modification de graphe Maven. Aucun franchissement imprévu de slice productive, ni modification de POM, migration I1/I3, owner physique I2/I3, finaliseur métier ou domaine normatif.

Base : CURRENT V3 neuve et jetable, migrations livrées ; les gates canoniques peuvent réexécuter des tests historiques transitifs, qui ne sont pas présentés comme un bootstrap I4 ou une baseline V2 certifiée.

## Implémentation

`DiscoveryEngine` porte la préparation cohérente, le calcul du préfixe global non-burnable, l’admission sous partition et le tour burnable volatil. Les ports séparent références non-burnables, références PENDING et admission. Les adaptateurs retiennent les protections PostgreSQL réelles, INSERT strict et CAS exacts ; les unités physiques restent possédées par les owners Spring I2/I3 injectés. Aucune table durable supplémentaire, lifecycle, claim, terminalisation ou cache faisant autorité n’est ajouté.

La stratégie B conserve la source PENDING protégée par `FOR UPDATE SKIP LOCKED` puis relit l’existence MVCC de tout slot, Q comprise. `examined`, `advanced` et `committedAdmissions` sont distincts ; la frontière sans capacité reste devant le curseur, lequel n’est retourné qu’après résolution physique confirmée. Une issue inconnue ne produit aucun résultat de progrès supposé.

Le script administratif ciblé `scripts/v3/grant-l2-discovery.sql` ajoute l’union exacte lifecycle/Discovery au principal commun de chaque autorité. I1 strict et I3 lifecycle-only restent les profils par défaut ; le profil combiné est explicite, sans fallback ni SET ROLE. Principaux administratif et réactivation distincts, absence de droits DDL/ownership/routage/purge supplémentaires.

Les sources productives raccordées sont Command, POT_EVENT et COMMAND_COMPLETION_EVENT. Les cinq familles de journaux F-N et la source IDENTITY_REQUEST des scénarios burnables restent des fixtures nommées ; l’autorité Identity de fixture et les admissions SQL internes ne sont jamais présentées comme producteurs métier L3/L5. Les factories refusent les sources productives absentes. Aucun Worker V3, endpoint HTTP ou parcours Bruno nouveau n’est activé.

## Campagnes de construction et défauts rencontrés

Les logs diagnostiques sont conservés dans `docs/testing/evidence/L2-I4/` ; ils n’établissent pas la certification du futur SHA figé.

- I4-1 : unités Engine, 8 nouvelles méthodes ; premier lancement initial sans ces méthodes, puis relance les incluant réellement.
- I4-2 : READ JDBC et droits réels non propriétaires.
- I4-3 : première slice COMMAND, cinq premières méthodes PostgreSQL Discovery, puis extension immédiate des preuves.
- I4-4/5 : 36 méthodes N/B ; premier diagnostic : une erreur de fixture expirant la mauvaise définition de Consumption, une assertion B18 incluant à tort le témoin p1 dans l’ensemble attendu p0. Corrections du harnais, puis 36/36 passées sans skip. Aucun défaut de protocole productif démontré par ces deux échecs.
- I4-6 : première campagne complète 49/49, zéro erreur/échec/skip, 2 395,894 secondes. Elle précède les compléments de variantes et ne certifie pas leur source ultérieure.
- I4-7 : slice COMMAND étendue, 1 573 tests exécutés, zéro échec/erreur, un skip historique ; les 49 méthodes Discovery et les deux méthodes de composition ont réellement passé. Cette campagne précède les derniers compléments de mesures et de matrice.
- Dernier diagnostic ciblé : 14 tests exécutés, une erreur X07 de préparation administrative, qui affectait aussi les partitions jamais détenues (owned_until non nul avec token nul). PostgreSQL a refusé l’UPDATE atomiquement. Correction limitée à p0 effectivement détenue ; relance : 27 tests, zéro échec/erreur/skip, dont cinq méthodes PostgreSQL sélectionnées, huit unités et quatorze méthodes d’architecture. Les quatre combinaisons Pot/EUI et le compteur bloqué sont incluses. Les compléments finaux de trace sont vérifiés par les gates figés.

Les commandes exactes et totaux diagnostiques sont conservés dans [Diagnostics-Summary.json](../testing/evidence/L2-I4/Diagnostics-Summary.json), avec empreintes des logs. Les premières archives diagnostiques peuvent contenir des XML antérieurs ; elles ne sont pas utilisées comme preuve de fraîcheur de clôture. Le XML séparé de la relance initiale à 36 méthodes n’a pas été archivé avant les campagnes suivantes ; sa sortie réelle est conservée. Les 49 méthodes sont réexécutées et archivées fraîchement dans chacun des gates finaux concernés.

Les campagnes finales sont exécutées sans filtre sur un seul commit de code figé, avec XML frais, traces, empreintes de sources avant/après et classes compilées. Les diagnostics filtrés restent identifiés comme diagnostics ; aucun test requis n’est déclaré PASS par présence dans le source.

## Gates canoniques sur code figé

Commandes réellement exécutées depuis `app/`, par le collecteur, sans filtre ni exclusion :

```sh
./mvnw -pl runtime-command-consumption-worker -am test
./mvnw -pl infra-persistence-read-jdbc -am test
./mvnw -pl runtime-monolith -am test
./mvnw -pl architecture-tests -am test
```

Résultats de clôture, quatre gates sans filtre au même SHA :

| Gate | Tests | Échecs/erreurs | Skips | Méthodes requises | Verdict |
|---|---:|---:|---:|---:|---|
| COMMAND | 1573 | 0 | 1 historique | 333 | PASS |
| READ JDBC | 372 | 0 | 0 | 10 | PASS |
| Monolithe | 1330 | 0 | 0 | 310 | PASS |
| Architecture globale | 1839 | 0 | 1 historique | 356 | PASS |

Le skip historique exact est `TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness` ; il reste SKIP et n’est pas une obligation I4. Les répétitions entre gates ne sont pas additionnées comme garanties distinctes. Les résultats et XML frais sont recensés dans les `final-*/result.json` et archives ; aucune méthode I4 requise n’est filtrée ou ignorée dans ces gates. Les 49 méthodes N01–N18/B01–B18/X01–X10/P01–P03 ont déjà passé fraîchement dans COMMAND, monolithe et architecture sur le SHA figé.

Le gate architecture est global ou quasi global par sa fermeture Maven ; son coût et ses tests historiques transitifs sont conservés. La commande full reactor `./mvnw test` n’a pas été exécutée : elle n’est pas requise pour cette clôture bornée, et aucun élargissement de graphe/owner productif n’est intervenu.

## Preuves et limites

Le [manifeste de preuves](L2_I4_Proof_Manifest.json) énumère les 49 méthodes et leurs variantes physiques obligatoires, plus les héritages I2/I3. Le collecteur `scripts/testing/verify-v3-l2-i4.py` refuse méthode absente/skippée/dupliquée/échouée, trace périmée/variante absente, source discordante et classe compilée manquante ; neuf contrôles du collecteur ont passé.

Le banc utilise PostgreSQL 17.11, image épinglée par digest, connexions distinctes, barrières SQL/advisory contrôlées, PID/xid, `pg_locks` et `pg_blocking_pids`, horloges serveur et lectures SQL extérieures. Le proxy filaire différencie coupure avant COMMIT et suppression de la réponse COMMIT réellement reçue du serveur. Le contre-exemple A naïve provoque volontairement 40P01 dans un scénario SQL isolé annulé ; ce n’est pas le protocole B livré.

Les coûts locaux conservent cinq échauffements et dix échantillons par configuration, plans EXPLAIN ANALYZE/BUFFERS/WAL, requêtes et lignes JDBC, allocations client cumulées et borne supérieure WAL du cluster. Les mesures séquentielles sur connexions physiques conservées isolent le coût SQL, excluent l’acquisition répétée de connexion et ne prouvent pas les pools I5. L’instrumentation de durées donne des bornes conservatrices, entre acquisition réelle et commit confirmé par horloge PostgreSQL, avec surcoût de mesure inclus ; quantiles descriptifs, aucune borne universelle ni SLO. Les INSERT d’analyse sont exécutés seulement sur cible jetable puis annulés.

## Coûts locaux observés

L’[analyse dérivée](../testing/evidence/L2-I4/Performance-Summary.json) est reproductible par [sa recette](../testing/evidence/L2-I4/Performance-Summary-Recipe.txt), appliquée aux archives vérifiées. Par gate comprenant Discovery : 918 configurations P01 (51 jeux × 18 combinaisons W/B/cap), 18 configurations P02 et 18 configurations P03 ; dix échantillons mesurés chacune. Les cinq échauffements P02/P03 sont individuellement tracés ; P01 exécute sa boucle fixe -5…9 sur des namespaces durables neufs, mais ne conserve les timings individuels que pour 0…9. Les quantiles dérivés excluent les échauffements. Les scénarios de concurrence B18/majorité verrouillée conservent leurs issues physiques contrôlées séparément ; ils ne constituent pas une campagne statistique de SLO.

Illustrations du gate COMMAND, PostgreSQL 17.11, environnement et paramètres archivés ; latence du harnais instrumenté, pas latence de production :

| Configuration | Médiane de l’appel | Maximum sur 10 | Borne supérieure du lock partition, médiane / max |
|---|---:|---:|---:|
| P01 POT technique, N10000, 25% local, W128/B32/cap64 | 22,070 ms | 49,291 ms | Non instrumenté dans cet exemple ; durées de possession mesurées en P02/P03/B18 |
| P02 Command, N10000, page32, population globale mixte | 20,160 ms | 24,920 ms | 18,265 / 22,953 ms |
| P03 Command, stock10000, Q90%, cap plein | 7,383 ms | 8,222 ms | 4,127 / 4,314 ms |

COUNT reste un parcours proportionnel au stock/index réellement choisi ; aucune promesse O(1). Les plans source, COUNT et INSERT annulé incluent buffers/WAL ; les compteurs JDBC représentent les statements applicatifs explicites et les lignes réellement consommées, avec requêtes d’instrumentation identifiées. Le WAL global peut inclure d’autres activités du cluster, les statistiques PostgreSQL sont asynchrones. Aucune comparaison d’amélioration productive n’est déduite des durées des campagnes diagnostiques : la topologie du harnais a changé pour conserver les connexions séquentielles.

## Graphe physique vérifié

Le protocole I2 reste : slot protégé → EUI de fixture protégée → verrou advisory/ligne Pot → révisions métier → résultat et FK vers la source → statut TERMINAL → DELETE du slot → compteurs et publications. La FK du résultat entraîne un verrou KEY SHARE de source avant l’UPDATE ; le banc s’arrête réellement avant et après cette acquisition, après l’UPDATE et après le DELETE.

Discovery B protège contrat/partition, puis chaque source locale par FOR UPDATE SKIP LOCKED et relit le slot dans un statement distinct sans verrou de ligne. Les seuls FK techniques vont au contrat de routage ; PK slot=(C,id), checkpoint=(C,p), ownership=(C,p). Aucun FK source ni nouvelle contrainte n’est introduit par I4. Les contraintes uniques et triggers existants restent actifs. La preuve de détention source q1/q3/q4 utilise aussi des probes concurrents FOR UPDATE SKIP LOCKED ; `pg_locks` seul ne suffit pas.

| Position du finaliseur F | Interaction réellement observée avec D | Résolution requise |
|---|---|---|
| Avant FK source, slot retenu | D protège la source ; F attend D sur sa FK ; la lecture MVCC de D voit le slot sans attendre F | D committe pendant F ouverte, puis F finit |
| Après FK, UPDATE ou DELETE non committé | D saute la source protégée ; aucun INSERT, aucune attente D→slot | D retourne avant libération de F ; commit ou rollback de F établis extérieurement |
| Deux F, Pot/EUI communs ou distincts et compteur retenu | Attentes réelles EUI, Pot/advisory ou compteur ; D résout pendant les F maintenues | Libération contrôlée du compteur, finalisation/reprise I3, une seule complétion par Command |
| Lot B18 | D retient q1, Q q3 et frontière q4 ; q2 de F sautée, q5 non examinée | exam4/adv3/admission1, curseur q3/U q5 ; variantes commit/rollback, reprise q4/q5 et Q intacte |
| Réactivation / renewals | Réactivation Q concurrente peut dépasser cap conformément à R2 ; p0 renewal saute partition D, p1/claim indépendant avance | Droits réels et état final observés ; aucun mécanisme I5 déduit |

Les attentes de relation INSERT/CAS sont provoquées et mesurées séparément en N18, avec timeout ou expiration puis libération avant timeout : horloge fraîche et rollback intégral. Les renouvellements de claims ne passent pas par Discovery. Aucun deadlock structurel de B n’a été démontré ; le 40P01 du scénario A naïve est un contre-exemple isolé volontaire, annulé intégralement.

La capture nommée « immediately before first Discovery » dans la fixture désigne son premier assemblage via `f.engine()` ; les harnais de coûts construisent directement leur Engine et possèdent leur capture explicite complète avant les appels mesurés. Les horloges et l’ordre effectif des captures font foi.

## Reports conservés

I5 : boucles, cadences, rotation et équité intégrée, tous emprunteurs et ressources de renouvellement, instrumentation Worker. L3 : producteurs et finaliseur Identity/EUI réels. L4/L5 : projections et Invitation métier, HTTP/consentement/Bruno. L6/L7/L8 : reconstruction READ/READY, SLO et charge globale, convergence et packaging. Aucun report ni ligne d’allocation normative L2 n’est promu par ce rapport.

## Publication et clôture

Code figé : `0b08d3bf0bfce1334260cfd5249b13c3040a67bb`. [Source-Freeze.json](../testing/evidence/L2-I4/Source-Freeze.json) confirme 272 fichiers protégés inchangés et le plan approuvé identique octet pour octet. `app/`, scripts et entrées du banc sont propres. Les quatre gates passent, avec 1 281 entrées et modes Git identiques, aucun échec/erreur, aucun skip I4, et 402 obligations de fichiers physiques vérifiées dans COMMAND et architecture (394 dans monolithe, deux dans READ). La tête distante reconfirmée avant publication reste au SHA approuvé ; aucun delta concurrent à intégrer. Livraison code + preuves publiée par push normal au SHA `88ef9f92cd5eb7988338f318884ac130a63cf994` ; `git ls-remote` vérifié identique et divergence 0/0, worktree propre avant ce reçu. Le [reçu de publication](../testing/evidence/L2-I4/Publication-Receipt.json) conserve ce contrôle et le [log du push](../testing/evidence/L2-I4/Publication-Delivery-Push.log). Le dernier commit documentaire consigne ce reçu sans modifier le code testé ; son SHA de tête finale est communiqué après vérification du second push normal. Aucun défaut substantiel démontré ne reste ouvert dans le périmètre borné ; les limites et reports ci-dessus restent applicables. Le [guide des preuves](../testing/evidence/L2-I4/README.md) et les reçus d’archives permettent leur reproduction. La [liste exacte des fichiers](../testing/evidence/L2-I4/Changed-Files.txt) est livrée avec le reçu ; checkout principal, branche et liste des travaux préexistants inchangés, sans aucune opération de nettoyage/reset. Contrôle de whitespace conforme sur code/scripts/docs V3 ; les journaux bruts sont conservés octet pour octet, y compris leurs espaces de sortie.

**I4 IMPLEMENTED — AWAITING INDEPENDENT CODE AND EVIDENCE AUDIT.**
